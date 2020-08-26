(ns com.fulcrologic.guardrails-pro.static.function-type
  "Extensible mechanism for calculating the type description of a function call."
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.utils :as grp.u]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [taoensso.timbre :as log]))

(defmulti calculate-function-type
  (fn [env sym argtypes]
    (let [{::grp.art/keys [arities]} (grp.art/function-detail env sym)
          {::grp.art/keys [gspec]} (grp.art/get-arity arities argtypes)
          {::grp.art/keys [pure? typecalc]} gspec]
      (cond
        pure? :pure
        typecalc (cond
                   (keyword? typecalc) typecalc
                   (map? typecalc) (::grp.art/dispatch typecalc)
                   (vector? typecalc) (first typecalc)
                   :else (do
                           (log/error "Typecalc invalid:" typecalc)
                           :default))
        :else :default))))

(>defn validate-argtypes!
  [env sym argtypes]
  [::grp.art/env qualified-symbol? (s/coll-of ::grp.art/type-description) => any?]
  (let [{::grp.art/keys [arities]} (grp.art/function-detail env sym)
        {::grp.art/keys [gspec]} (grp.art/get-arity arities argtypes)
        {::grp.art/keys [arg-specs arg-predicates]} gspec]
    (doseq [[arg-spec {::grp.art/keys [samples original-expression]}]
            (map vector arg-specs argtypes)
            :let [checkable? (and arg-spec (seq samples))]]
      (when-not checkable?
        (grp.art/record-warning! env
          {::grp.art/original-expression original-expression
           ::grp.art/message (str "Could not check " original-expression ".")}))
      (when-let [failing-sample (and checkable?
                                  (some (fn _invalid-sample [sample]
                                          (when-not (s/valid? arg-spec sample) sample))
                                    samples))]
        (grp.art/record-error! env
          {::grp.art/original-expression original-expression
           ::grp.art/expected gspec
           ::grp.art/actual {::grp.art/failing-samples [failing-sample]}
           ::grp.art/message (str "Function argument " original-expression " failed to pass spec " arg-spec " on failing sample " failing-sample ".")})))
    (doseq [sample-arguments (apply map vector (map ::grp.art/samples argtypes))]
      (doseq [arg-pred arg-predicates
              :when (every? (partial apply s/valid?)
                      (map vector arg-specs sample-arguments))]
        (when-not (apply arg-pred sample-arguments)
          (grp.art/record-error! env
            {::grp.art/original-expression (map ::grp.art/original-expression argtypes)
             ::grp.art/actual {::grp.art/failing-samples sample-arguments}
             ::grp.art/message (str "Function arguments " (map ::grp.art/original-expression argtypes) "failed to pass predicate " arg-pred " on failing sample " sample-arguments ".")})))))
  :done)

(defmethod calculate-function-type :default [env sym argtypes]
  (let [{::grp.art/keys [arities]} (grp.art/function-detail env sym)
        {::grp.art/keys [gspec]} (grp.art/get-arity arities argtypes)
        {::grp.art/keys [return-type return-spec]} gspec]
    (validate-argtypes! env sym argtypes)
    {::grp.art/spec return-spec
     ::grp.art/type return-type
     ::grp.art/samples (grp.u/try-sampling gspec)}))

(defmethod calculate-function-type :pure [env sym argtypes]
  (let [{::grp.art/keys [fn-ref]} (grp.art/function-detail env sym)]
    (validate-argtypes! env sym argtypes)
    {::grp.art/samples (apply map fn-ref (map ::grp.art/samples argtypes))}))

(defmethod calculate-function-type :reduce-like [env sym argtypes]
  ;; TODO
  )

(defmethod calculate-function-type :map-like [env sym argtypes]
  ;; TODO
  )
