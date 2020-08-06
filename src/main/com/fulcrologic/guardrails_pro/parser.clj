(ns com.fulcrologic.guardrails-pro.parser
  "Implementation of reading >defn for macro expansion."
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.guardrails-pro.static.forms :as forms]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [taoensso.timbre :as log]
    [clojure.spec.alpha :as s])
  (:import (clojure.lang Cons)
           (java.util Date)))

(defn function-name
  [[result [nm :as args]]]
  (if (simple-symbol? nm)
    [result (next args)]
    (throw (ex-info "Missing function name." {}))))

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
            (update ::a/arg-types (fnil conj []) (pr-str a))
            (update ::a/arg-specs (fnil conj []) a))
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
            (update r ::a/arg-predicates (fnil conj []) (replace-args a arglist))
            as
            (first as)
            (next as))
          (throw (ex-info "Syntax error. Expected return type." {})))))
    env))

(defn return-type [[result [lookahead t & remainder :as args]]]
  (if (#{:ret '=>} lookahead)
    [(assoc result
       ::a/return-spec t
       ::a/return-type (pr-str t)) remainder]
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
            (update r ::a/return-predicates (fnil conj []) a)
            as
            (first as)
            (next as))
          [r args])))
    env))

(defn generator [[result [lookahead & remainder :as args] :as env]]
  (if (#{:gen '<-} lookahead)
    [(assoc result ::a/generator (first remainder)) []]
    env))

(defn parse-gspec [spec arglist]
  (let [md (or (meta spec) {})]
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
  [(assoc result (body-arity args) (with-meta
                                     {::a/arglist `(quote ~arglist)
                                      ::a/gspec   (parse-gspec spec arglist)
                                      ::a/body    (forms/form-expression (vec forms))}
                                     {::a/raw-body (vec forms)}))])

(defn multiple-arities [[result args]]
  (loop [r           result
         next-body   (first args)
         addl-bodies (next args)]
    (if next-body
      (do
        (when-not (arity-body? next-body)
          (throw (ex-info "Syntax error. Multi-arity function body expected." {})))
        (let []
          (recur
            (first (single-arity [r next-body]))
            (first addl-bodies)
            (next addl-bodies))))
      [r []])))

(defn function-content [[result [lookahead :as args] :as env]]
  (if (arity-body? lookahead)
    (multiple-arities env)
    (single-arity env)))

(defn parse-defn-args
  "Parses the body of a function and returns a map describing what it found."
  [args]
  (first
    (-> [{} args]
      (function-name)
      (optional-docstring)
      (function-content))))

(comment
  (parse-defn-args '(nm "hello"
                      [a] [string? | #(not (empty? a)) => int?]
                      (str a)))
  (parse-defn-args '(nm "hello"
                      ([a] [string? | #(> 1 a) => int? | #(pos-int? %)] (+ 1 a))
                      ([a b] [string? string? => int?] (+ a b))
                      ([a b & more] [string? string? (s/+ int?) => int?] (apply + a b more)))))