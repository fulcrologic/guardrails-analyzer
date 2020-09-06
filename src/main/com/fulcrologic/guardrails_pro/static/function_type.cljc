(ns com.fulcrologic.guardrails-pro.static.function-type
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.sampler :as grp.sampler]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]))

(>defn validate-argtypes!
  [env sym actual-argument-type-descriptors]
  [::grp.art/env qualified-symbol? (s/coll-of ::grp.art/type-description) => any?]
  (let [{::grp.art/keys [arities]} (grp.art/function-detail env sym)
        {::grp.art/keys [gspec]} (grp.art/get-arity arities actual-argument-type-descriptors)
        {::grp.art/keys [arg-specs arg-types arg-predicates]} gspec]
    (doseq [[arg-spec human-readable-expected-type {::grp.art/keys [samples original-expression]}]
            (map vector arg-specs arg-types actual-argument-type-descriptors)
            :let [checkable? (and arg-spec (seq samples))]]
      (when-not checkable?
        (grp.art/record-warning! env original-expression (str "Could not check " original-expression ".")))
      (when-let [{:keys [failing-sample]} (and checkable?
                                            (some (fn _invalid-sample [sample]
                                                    (when-not (s/valid? arg-spec sample)
                                                      {:failing-sample sample}))
                                              samples))]
        (grp.art/record-error! env
          {::grp.art/original-expression original-expression
           ::grp.art/expected            gspec
           ::grp.art/actual              {::grp.art/failing-samples #{failing-sample}}
           ::grp.art/message             (str "Function argument " (pr-str original-expression)
                                           " failed to pass spec " human-readable-expected-type
                                           ". (expression sample that failed: " (pr-str failing-sample) ").")})))
    (doseq [sample-arguments (apply map vector (map ::grp.art/samples actual-argument-type-descriptors))]
      (doseq [arg-pred arg-predicates
              :when (every? (partial apply s/valid?)
                      (map vector arg-specs sample-arguments))]
        (when-not (apply arg-pred sample-arguments)
          (grp.art/record-error! env
            {::grp.art/original-expression (map ::grp.art/original-expression actual-argument-type-descriptors)
             ::grp.art/actual              {::grp.art/failing-samples (set sample-arguments)}
             ::grp.art/message             (str "Function arguments " (pr-str (mapv ::grp.art/original-expression actual-argument-type-descriptors))
                                             "failed to pass argument predicate. Failing sample arguments: " (pr-str sample-arguments) ".")})))))
  :done)

(defn calculate-function-type [env sym argtypes]
  (let [{::grp.art/keys [arities] :as fd} (grp.art/function-detail env sym)
        {::grp.art/keys [gspec]} (grp.art/get-arity arities argtypes)
        {::grp.art/keys [return-type return-spec]} gspec]
    (log/info "Function type description: " sym ": " (with-out-str (pprint fd)))
    (validate-argtypes! env sym argtypes)
    {::grp.art/spec    return-spec
     ::grp.art/type    return-type
     ::grp.art/samples (grp.sampler/sample! env fd argtypes)}))
