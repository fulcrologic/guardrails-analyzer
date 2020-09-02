(ns com.fulcrologic.guardrails-pro.parser
  "Implementation of reading >defn for macro expansion."
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.forms :as forms]
    [com.fulcrologic.guardrails.core :refer [>defn =>]])
  (:import
    (clojure.lang Cons)))

(defn function-name
  [[result [nm :as args]]]
  (if (simple-symbol? nm)
    [result (next args)]
    (throw (ex-info (format "%s is missing function name." nm) {}))))

(defn optional-docstring [[result [candidate :as args]]]
  (if (string? candidate)
    [result (next args)]
    [result args]))

(defn arg-specs [[result args :as env]]
  (loop [r    result
         args args
         a    (first args)
         as   (next args)]
    (if (#{'| '=> :st :ret} a)
      [r args]
      (if a
        (recur
          (-> r
            (update ::grp.art/arg-types (fnil conj []) (pr-str a))
            (update ::grp.art/arg-specs (fnil conj []) a))
          as
          (first as)
          (next as))
        (throw (ex-info "Syntax error in argument spec. Expected a return type." {}))))))

(defn replace-args [lambda new-arglist]
  (apply list (first lambda) new-arglist (rest (rest lambda))))

(defn arg-predicates [[result [lookahead & remainder :as args] :as env] arglist]
  (if (#{:st '|} lookahead)
    (loop [r    result
           args remainder
           a    (first remainder)
           as   (next remainder)]
      (if (#{:ret '=>} a)
        [r args]
        (if a
          (recur
            (update r ::grp.art/arg-predicates (fnil conj []) (replace-args a arglist))
            as
            (first as)
            (next as))
          (throw (ex-info "Syntax error. Expected return type." {})))))
    env))

(defn return-type [[result [lookahead t & remainder :as args]]]
  (if (#{:ret '=>} lookahead)
    [(assoc result
       ::grp.art/return-spec t
       ::grp.art/return-type (pr-str t)) remainder]
    (throw (ex-info "Syntax error. Expected return type" {}))))

(defn such-that [[result [lookahead & remainder :as args] :as env]]
  (if (#{:st '|} lookahead)
    (loop [r    result
           args remainder
           a    (first args)
           as   (next args)]
      (if (#{:gen '<-} a)
        [r args]
        (if a
          (recur
            (update r ::grp.art/return-predicates (fnil conj []) a)
            as
            (first as)
            (next as))
          [r args])))
    env))

(defn generator [[result [lookahead & remainder :as args] :as env]]
  (if (#{:gen '<-} lookahead)
    [(assoc result ::grp.art/generator (first remainder)) []]
    env))

(defn parse-gspec [result spec arglist]
  (let [md (merge (or (meta spec) {})
             (::fn-meta result {}))]
    (first
      (-> [md spec]
        (arg-specs)
        (arg-predicates arglist)
        (return-type)
        (such-that)
        (generator)))))

(defn arity-body? [b] (or (instance? Cons b) (list? b)))

(defn body-arity
  [[arglist & _]]
  (if (contains? (set arglist) '&)
    :n
    (count arglist)))

(defn single-arity [[result [arglist spec & forms :as args]]]
  [(assoc result (body-arity args)
     (with-meta
       {::grp.art/arglist `(quote ~arglist)
        ::grp.art/gspec   (parse-gspec result spec arglist)
        ::grp.art/body    (forms/form-expression (vec forms))}
       {::grp.art/raw-body `(quote ~(vec forms))}))])

(defn multiple-arities [[result args]]
  (loop [r           result
         next-body   (first args)
         addl-bodies (next args)]
    (if next-body
      (do
        (when-not (arity-body? next-body)
          (throw (ex-info "Syntax error. Multi-arity function body expected." {})))
        (recur
          (first (single-arity [r next-body]))
          (first addl-bodies)
          (next addl-bodies)))
      [r []])))

(defn function-content [[result [lookahead :as args] :as env]]
  (if (arity-body? lookahead)
    (multiple-arities env)
    (single-arity env)))

(>defn parse-defn-args
  "Takes the body of a defn and returns parsed arities and top level location information."
  [[defn-sym :as args]]
  [seq? => (s/keys :req [::grp.art/arities ::grp.art/location])]
  (let [arities (first
                  (-> [{} args]
                    (function-name)
                    (optional-docstring)
                    (function-content)))]
    {::grp.art/arities arities
     ::grp.art/location (grp.art/new-location (meta defn-sym))}))

(defn var-name
  [[result [nm :as args]]]
  (if (qualified-symbol? nm)
    [(assoc result ::fn-meta
       (set/rename-keys (meta nm)
         {:pure? ::grp.art/pure?}))
     (next args)]
    (throw (ex-info (format "%s is not fully qualified symbol" nm) {}))))

(defn parse-fdef-args [args]
  (-> [{} args]
    (var-name)
    (function-content)
    first
    (dissoc ::fn-meta)))

(comment
  (parse-fdef-args '(x/nm [a] [a => c]))
  (parse-fdef-args '(x/nm ([a] [a => c]) ([a b] [a b => c])))

  (parse-defn-args '(nm "hello"
                      [a] [string? | #(not (empty? a)) => int?]
                      (str a)))
  (parse-defn-args '(nm "hello"
                      ([a] [string? | #(> 1 a) => int? | #(pos-int? %)] (+ 1 a))
                      ([a b] [string? string? => int?] (+ a b))
                      ([a b & more] [string? string? (s/+ int?) => int?] (apply + a b more)))))
