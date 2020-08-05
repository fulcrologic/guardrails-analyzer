(ns com.fulcrologic.guardrails-pro.static.function-type
  "Extensible mechanism for calculating the type description of a function call."
  (:require
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails.core :refer [>defn => |]]
    [clojure.spec.alpha :as s]))

(defmulti calculate-function-type
  "Use `get-calculate-function-type`. This is a multimethod that you use defmethod on to extend the type recognition system."
  (fn [env sym argument-type-descriptions]
    ;; TASK: Look up sym definition, figure out correct arity, look up pure? vs typecalc, dispatch on typecalc (or default)
    (let [dispatch-key (::return-type env)]
      (cond
        (vector? dispatch-key) (first dispatch-key)
        (keyword? dispatch-key) dispatch-key
        (map? dispatch-key) (::dispatch dispatch-key)))))

(defmethod calculate-function-type :default [env sym argtypes]
  ;; TASK: Use env to find spec of return type...honor pure if possible...
  )

(defmethod calculate-function-type :pure [env sym argtypes]
  ;; TASK: Use env to find the argument samples and the artifact repository to find the function value (which is callable),
  ;; and generate a ::a/calculate-function-type by calling the function on the argument samples.
  )

(defmethod calculate-function-type :HOF [env sym argtypes]
  ;; TASK: This one dispatches on [:HOF arg-number]. The arg number indicates which argument of the function
  ;; is the HOF of interest. For example, `reduce` should use [:HOF 0] to indicate that its return type is
  ;; whatever type its first argument (a HOF) returns.
  )

(defmethod calculate-function-type :map-like [env sym argtypes]
  ;; TASK:
  )
