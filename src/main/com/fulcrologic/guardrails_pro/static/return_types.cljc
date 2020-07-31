(ns com.fulcrologic.guardrails-pro.static.return-types
  (:require
    [clojure.spec.alpha :as s]))

(s/def ::dispatch keyword?)
(s/def ::return-type (s/or
                       :kw ::dispatch
                       :map (s/keys :req [::dispatch])
                       :vec (s/and vector? #(s/valid? ::dispatch (first %)))))

(defmulti calculate-return-type
  "Calculate the return type of the function described in the given env, which contains the full function's
   captured information, the callsite environment, and the information necessary to dispatch this multimethod
   to the correct handler.

   The ::return-types/return-type key of metadata on the function's gspec type signature indicates how to calculate the return
   type. The value of this key can be:

   * A keyword: Used unmodified as the dispatch key to this multimethod
   * A vector: The first element is used as the dispatch key, and addl elements are usable by the multimethod itself.
   * A map: The ::return-types/dispatch key of the map is used as the dispatch key for this multimethod.

   The :default return type calculation trusts the literal spec (which can have a generator for samples).

   Higher-order function use and collection manipulation can then be described. For example:

   ```
   (>defn map
     [f coll]
     ^{::rt/return-type [:sequence-of-HOF 0 [1]]} [fn? seq? => seq?]
     ...)
   ```

   can be used to say \"The return type of this function is a sequence of calls to the higher-order function
   at arg index 0 using argment(s) at index 1 as the sequence\". Allowing the return type to be a vector or map allows
   any amount of custom detail that the multimethod can use to generate return samples from input samples.

   The :pure return type calculate indicates that the function itself can be safely called with sample input
   parameters to generate sample return values which should be considered authoritative coverage of the types of
   things expected from the function. The spec of the return type is still applied to these values to ensure that
   the resulting sample is sane.

   Using the `:auto-infer-hash-fns true` global option essentially causes the function to adopt the return spec of
   the last expression (if known) while also adopting the :pure return type calculation. This allows easy checks of
   things like `(map #(+ 1 %) [1 2 3])` where the return type of `map` is known to be a sequence of the return
   types of the HOF it is given. With the auto-infer (and a return type on `map` that indicates it is pure IF the HOF is pure)
   we can just run the map on input samples (or the literal in this case) to generate the new samples of the return type.
   "
  (fn [env]
    (let [dispatch-key (::return-type env)]
      (cond
        (vector? dispatch-key) (first dispatch-key)
        (keyword? dispatch-key) dispatch-key
        (map? dispatch-key) (::dispatch dispatch-key)))))

(defmethod calculate-return-type :default [env]
  ;; TASK: Use env to find spec of return type, and generate a ::a/type-description from it
  )

(defmethod calculate-return-type :pure [env]
  ;; TASK: Use env to find the argument samples and the artifact repository to find the function value (which is callable),
  ;; and generate a ::a/type-description by calling the function on the argument samples.
  )

(defmethod calculate-return-type :HOF [env]
  ;; TASK: This one dispatches on [:HOF arg-number]. The arg number indicates which argument of the function
  ;; is the HOF of interest. For example, `reduce` arity 4 should use [:HOF 1] to indicate that its return type is
  ;; whatever type its first argument (a HOF) returns.
  )

(defmethod calculate-return-type :sequence-of-HOF [env]
  ;; TASK: This one dispatches on [:sequence-of-HOF arg-number]. The arg number indicates which argument of the function
  ;; is the HOF of interest. For example, `map` should use [:sequence-of-HOF 1] to indicate that its return type is
  ;; a sequence of whatever type its first argument (a HOF) returns.
  )