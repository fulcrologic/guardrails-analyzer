(ns com.fulcrologic.guardrails-pro.parser
  (:require [taoensso.timbre :as log])
  (:import (clojure.lang Cons)))

(defn function-name [[result [nm :as args]]]
  (if (simple-symbol? nm)
    [(merge result {:name nm}) (next args)]
    (throw (ex-info "Missing function name." {}))))

(defn optional-docstring [[result [candidate :as args]]]
  (if (string? candidate)
    [(merge result {:docstring candidate}) (next args)]
    [{} args]))

(defn arg-specs [[result args :as env]]
  (loop [r    result
         args args
         a    (first args)
         as   (next args)]
    (if (#{'| '=> :st :ret} a)
      [r args]
      (if a
        (recur
          (update r :argument-types (fnil conj []) a)
          as
          (first as)
          (next as))
        (throw (ex-info "Syntax error in argument spec. Expected a return type." {}))))))

(defn arg-predicates [[result [lookahead & remainder :as args] :as env]]
  (if (#{:st '|} lookahead)
    (loop [r    result
           args remainder
           a    (first remainder)
           as   (next remainder)]
      (if (#{:ret '=>} a)
        [r args]
        (if a
          (recur
            (update r :argument-predicates (fnil conj []) a)
            as
            (first as)
            (next as))
          (throw (ex-info "Syntax error. Expected return type." {})))))
    env))

(defn return-type [[result [lookahead t & remainder :as args]]]
  (if (#{:ret '=>} lookahead)
    [(assoc result :return-type t) remainder]
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
            (update r :return-predicates (fnil conj []) a)
            as
            (first as)
            (next as))
          [r args])))
    env))

(defn generator [[result [lookahead & remainder :as args] :as env]]
  (if (#{:st '|} lookahead)
    [(assoc result :generator (first remainder)) []]
    env))

(defn parse-gspec [spec]
  (first
    (-> [{} spec]
      (arg-specs)
      (arg-predicates)
      (return-type)
      (such-that)
      (generator))))

(defn arity-body? [b] (or (instance? Cons b) (list? b)))

(defn body-arity [[arglist & _]]
  (keyword (str "arity-"
             (if (contains? (set arglist) '&)
               "n"
               (count arglist)))))

(defn single-arity [[result [arglist spec & forms :as args]]]
  [(assoc result (body-arity args) {:arglist arglist
                                    :gspec   (parse-gspec spec)
                                    :forms   forms})])

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

(defn parse-function
  "Parses the body of a function and returns a map describing what it found."
  [args]
  (first
    (-> [{} args]
      (function-name)
      (optional-docstring)
      (function-content))))


(comment
  (parse-function '(nm "hello"
                     [a] [string? => int?]
                     (str a)
                     ))
  (parse-function '(nm "hello"
                     ([a] [string? => int?] (+ 1 a))
                     ([a b] [string? string? => int?] (+ a b))
                     ([a b & more] [string? string? => int?] (apply + a b more)))))