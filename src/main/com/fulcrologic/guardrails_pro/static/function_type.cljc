(ns com.fulcrologic.guardrails-pro.static.function-type
  "Extensible mechanism for calculating the type description of a function call."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-pro.interpreter :as grp.i]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails.core :refer [>defn => |]]
    [taoensso.timbre :as log]))

(defmulti calculate-function-type
  "Use `get-calculate-function-type`. This is a multimethod that you use defmethod on to extend the type recognition system."
  (fn [env sym argument-type-descriptions]
    (let [{::a/keys [fn arities]} (a/function-detail env sym)
          nargs (count argument-type-descriptions)
          {::a/keys [gspec] :as function-description} (get arities nargs (get arities :n))
          {::a/keys [pure? typecalc]} gspec]
      (cond
        pure? :pure
        typecalc (cond
                   (keyword? typecalc) typecalc
                   (map? typecalc) (::a/dispatch typecalc)
                   (vector? typecalc) (first typecalc)
                   :else (do
                           (log/error "Typecalc invalid:" typecalc)
                           :default))
        :else :default))))

(defn validate-argtypes [{::a/keys [arg-specs arg-predicates]} argtypes]
  (mapcat
    (fn [[arg-spec {::a/keys [samples original-expression]}]]
      (mapcat
        (fn [sample]
          (concat
            (some->>
              (s/explain-data arg-spec sample)
              ::s/problems
              (map (fn [e]
                     {::a/original-expression original-expression
                      ::a/expected (:pred e)
                      ::a/actual (:val e)})))
            (keep (fn [pred]
                    (or (pred sample)
                      {::a/original-expression original-expression
                       ::a/expected pred
                       ::a/actual sample}))
              arg-predicates)))
        samples))
    (map vector arg-specs argtypes)))

(defmethod calculate-function-type :default [env sym argtypes]
  (let [{::a/keys [arities]} (a/function-detail env sym)
        {::a/keys [gspec]} (get arities (count argtypes) :n)
        {::a/keys [return-type return-spec generator]} gspec]
    {::a/spec return-spec
     ::a/type return-type
     ;; TODO: such-that return-predicates
     ::a/samples (or (and generator (gen/sample generator))
                   (grp.i/try-sampling return-spec))
     ::a/errors (validate-argtypes gspec argtypes)
     }))

(defmethod calculate-function-type :pure [env sym argtypes]
  ;; TASK: Use env to find the argument samples and the artifact repository to find the function value (which is callable),
  ;; and generate a ::a/calculate-function-type by calling the function on the argument samples.

  ;; probably can just return ::a/samples and ::a/errors
  )

(defmethod calculate-function-type :HOF [env sym argtypes]
  ;; TASK: This one dispatches on [:HOF arg-number]. The arg number indicates which argument of the function
  ;; is the HOF of interest. For example, `reduce` should use [:HOF 0] to indicate that its return type is
  ;; whatever type its first argument (a HOF) returns.
  )

;; # => fn*   (setting that says fn* are always pure)
;; (map #(+ 1 2) [1 2 3])
;; #{[1 2 3] [4 2] ...}
(defmethod calculate-function-type :map-like [env sym argtypes]
  ;; TASK:
  )
