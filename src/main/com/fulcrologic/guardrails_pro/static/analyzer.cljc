(ns com.fulcrologic.guardrails-pro.static.analyzer
  "The analyzer for expressions. An extensible process where recognition of special forms can happen.

   The type of an expression is often determined at this phase (e.g. for literals), and this phase is
   also responsible for


   The type description of ANY expression is defined by:

   ```
   (type-description env expr)
   ```

   which is a mutually-recursive algorithm with the following steps:

   * Call `analyze` on the expression.
   * If it is a call to a function in the registry, then we dispatch to a multimethod that can
     do proper steps for type extraction (which may call analyze on arguments to get type
     descriptors).
   ** Analyze may find it to be trivially a type (e.g. primitive/literal)
   ** The analyzer can detect special forms (i.e. `let`) and process them, which in turn
      will need to ask for the `type-description` of each binding value.
      NOTE: There can be any number of errors detected during this analysis.
   ** The end result is a type-description (which can include nested errors)
   "
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.static.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.utils :as grp.u])
  (:import
    (java.util.regex Pattern)))

(defn regex? [x]
  (= (type x) Pattern))

(defn list-dispatch-type [sexpr]
  (if (symbol? (first sexpr))
    (case (str (first sexpr))
      "do"  :do
      ("let" "clojure.core/let") :let
      :function-call)
    :function-call))

(defmulti analyze
  (fn [env sexpr]
    (cond
      (seq? sexpr)    (list-dispatch-type sexpr)
      (symbol? sexpr)  :symbol

      (char?    sexpr) :literal
      (number?  sexpr) :literal
      (string?  sexpr) :literal
      (keyword? sexpr) :literal
      (regex?   sexpr) :literal
      (nil?     sexpr) :literal

      (vector?  sexpr) :collection
      (set?     sexpr) :collection
      (map?     sexpr) :collection

      :else :unknown)))

(defmethod analyze :literal [env sexpr]
  (let [spec (cond
               (char?    sexpr) char?
               (number?  sexpr) number?
               (string?  sexpr) string?
               (keyword? sexpr) keyword?
               (regex?   sexpr) regex?
               (nil?     sexpr) nil?)]
    {::a/spec spec
     ::a/original-expression sexpr}))

(defmethod analyze :symbol [env sym]
  (or (a/symbol-detail env sym) {}))

(defmethod analyze :function-call [env [function & arguments]]
  (let [{::a/keys [arities]} (a/function-detail env function)
        {::a/keys [gspec]} (get arities (count arguments) :n)
        {::a/keys [arg-specs]} gspec
        args-type-desc (mapv (partial analyze env) arguments)
        errors (mapcat
                 (fn [[arg-spec arg]]
                   (let [{::a/keys [spec]} (analyze env arg)]
                     (->> (grp.u/try-sampling {::a/return-spec spec})
                       (map (fn [sample]
                              (some->>
                                (s/explain-data arg-spec sample)
                                ::s/problems
                                (map (fn [e]
                                       {::a/original-expression arg
                                        ::a/expected (:pred e)
                                        ::a/actual (:val e)
                                        ::a/spec arg-spec})))))
                       (first))))
                 (map vector arg-specs arguments))]
    (cond-> (grp.fnt/calculate-function-type env function args-type-desc)
      (seq errors) (update ::a/errors concat errors))))

(defmethod analyze :do [env [_ & body]]
  (analyze
    (reduce (fn [env sexpr]
              (merge env (::a/errors (analyze env sexpr))))
      env (butlast body))
    (last body)))

(defmethod analyze :let [env [_ bindings & body]]
  (analyze (reduce (fn [env [bind-sym sexpr]]
                     (assoc-in env [::a/local-symbols bind-sym]
                       (analyze env sexpr)))
             env (partition 2 bindings))
    `(do ~@body)))

;; analyze :let
;; Walk each binding, recursively calling analyze, while adding bindings to env
;; collect type description errors from all bindings and "floating" expressions
;(let [a 1 ;; Add type description for 1 to env -> env'
;      b (f 2)] ; call analyze on `(f 2)` and bind type-desc to b in env''
;  (g b)) ; grand return of `let` is the type description of the LAST expression, but with cumulative ::errors

;; HOF case
;; (analyze env `(let [a (map + [1 2 3])] (f a)))
;; 1. dispatch to `let` analyzer, which:
;;   a. recursively calls (analyze env `(map + [1 2 3]))
;;      i. no special for map, so calls (function-type `map [type-desc-for-+ type-desc-for-vector-of-int])
;;      ii. Assume we've registered a gspec for + (pure) and map (seq of HOF on 1).
;;      iii. function-type detects it is safe to call + on each element of the sample sequence, to produce new sequence.
;;   b. new sequence of returned samples is bound into `a`
;;   c. runs (analyze env `(f a)) (w/a bound in env)
;;      i. analyze does not recognize `f` as special (uses :default), so it calls `(function-type `f [type-description-a])`
