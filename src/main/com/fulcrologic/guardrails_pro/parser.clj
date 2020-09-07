(ns com.fulcrologic.guardrails-pro.parser
  "Implementation of reading >defn for macro expansion."
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.forms :as forms]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [taoensso.timbre :as log])
  (:import
    (clojure.lang Cons)))

(defn function-name
  [[result [nm :as args]] & {:keys [optional?]}]
  (if (simple-symbol? nm)
    [result (next args)]
    (if optional? result
      (throw (ex-info (format "%s is missing function name." nm) {})))))

(defn optional-docstring [[result [candidate :as args]]]
  (if (string? candidate)
    [result (next args)]
    [result args]))

(defn derive-sampler-type [m]
  (if-let [hard-value (get m ::grp.art/dispatch)]
    hard-value
    (let [possible-values (reduce-kv (fn [acc k v]
                                       (cond-> acc
                                         (true? v) (conj k))) #{} m)]
      ;; TASK: if we do this analysis at runtime we can know all installed dispatch extensions. Little flaky to
      ;; do it at macro expansion time
      (when (< 1 (count possible-values))
        (log/warn "Multiple possible type propagation candidates for spec list" possible-values))
      (first possible-values))))

(defn arg-specs [[result argspecs :as env]]
  (let [sampler-type (log/spy :info (derive-sampler-type (meta argspecs)))]
    (loop [r        (cond-> result
                      sampler-type (assoc ::grp.art/sampler sampler-type))
           argspecs argspecs
           a        (first argspecs)
           as       (next argspecs)]
      (if (#{'| '=> :st :ret} a)
        [r argspecs]
        (if a
          (recur
            (-> r
              (update ::grp.art/arg-types (fnil conj []) (pr-str a))
              (update ::grp.art/arg-specs (fnil conj []) a))
            as
            (first as)
            (next as))
          (throw (ex-info "Syntax error in argument spec. Expected a return type." {})))))))

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
  (let [md (merge (::fn-meta result {})
             (or (meta spec) {}))]
    (log/spy :info :GSPEC (first
                            (-> [md spec]
                              (arg-specs)
                              (arg-predicates arglist)
                              (return-type)
                              (such-that)
                              (generator))))))

(defn arity-body? [b] (or (instance? Cons b) (list? b)))

(defn body-arity
  [[arglist & _]]
  (if (contains? (set arglist) '&)
    :n
    (count arglist)))

(defn single-arity [[result [arglist spec & forms :as args]]]
  [(assoc result (body-arity args)
                 (with-meta
                   {::grp.art/arglist (forms/form-expression arglist)
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

;; NOTE: Cannot have a spec from artifacts, because this is dealing with syntax parsing at macro time
(defn parse-defn-args
  "Takes the body of a defn and returns parsed arities and top level location information."
  [[defn-sym :as args]]
  (let [arities (first
                  (-> [{} args]
                    (function-name)
                    (optional-docstring)
                    (function-content)))]
    {::grp.art/arities  arities
     ::grp.art/location (grp.art/new-location (meta defn-sym))}))

(defn var-name
  [[result [nm :as args]]]
  (if (qualified-symbol? nm)
    [(assoc result ::fn-meta
                   (cond-> (meta nm)
                     (:pure? (meta nm)) (assoc ::grp.art/sampler :pure)))
     (next args)]
    (throw (ex-info (format "%s is not fully qualified symbol" nm) {}))))

(defn parse-fdef-args [args]
  (-> [{} args]
    (var-name)
    (function-content)
    first
    (dissoc ::fn-meta)))

(defn parse-fn-args [args]
  (-> [{} args]
    (function-name :optional? true)
    (function-content)
    first
    (dissoc ::fn-meta)))

(defn parse-fspec-args [args]
  (-> [{} args]
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
