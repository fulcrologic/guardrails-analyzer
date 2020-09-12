(ns com.fulcrologic.guardrails-pro.static.function-type
  (:require
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.utils :as grp.u]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]))

(>defn bind-type-desc
  [env typename clojure-spec err]
  [::grp.art/env ::grp.art/type ::grp.art/spec map? => ::grp.art/type-description]
  (let [samples (grp.sampler/try-sampling! env (s/gen clojure-spec) err)]
    (cond-> {::grp.art/spec clojure-spec
             ::grp.art/type typename}
      (seq samples) (assoc ::grp.art/samples samples))))

(>defn bind-argument-types
  [env arity-detail]
  [::grp.art/env ::grp.art/arity-detail => ::grp.art/env]
  (let [{::grp.art/keys [gspec arglist]} arity-detail
        {::grp.art/keys [arg-specs arg-types]} gspec]
    (log/spy :warn [arglist (meta arglist) (meta (first arglist))])
    (reduce
      (fn [env [bind-sexpr arg-type arg-spec]]
        (reduce-kv grp.art/remember-local
          env (grp.u/destructure* env bind-sexpr
                (bind-type-desc env arg-type arg-spec
                  {::grp.art/original-expression arglist}))))
      env
      (map vector arglist arg-types arg-specs))))

(>defn check-return-type! [env {::grp.art/keys [body gspec]} {::grp.art/keys [samples]}]
  [::grp.art/env ::grp.art/arity-detail ::grp.art/type-description => any?]
  (let [{::grp.art/keys [return-spec return-type]} gspec
        sample-failure (some #(when-not (s/valid? return-spec %) {:failing-case %}) samples)]
    (when (contains? sample-failure :failing-case)
      (let [sample-failure (:failing-case sample-failure)]
        (grp.art/record-error! env
          {::grp.art/original-expression (last body)
           ::grp.art/actual              {::grp.art/failing-samples #{sample-failure}}
           ::grp.art/expected            {::grp.art/type return-type ::grp.art/spec return-spec}
           ::grp.art/message             (str "Return value (e.g. " (pr-str sample-failure) ") does not always satisfy the return spec of " return-type ".")})))))

(>defn validate-argtypes!
  [env sym actual-argument-type-descriptors]
  [::grp.art/env qualified-symbol? (s/coll-of ::grp.art/type-description) => any?]
  (let [{::grp.art/keys [arities]} (grp.art/function-detail env sym)
        {::grp.art/keys [gspec]} (grp.art/get-arity arities actual-argument-type-descriptors)
        {::grp.art/keys [arg-specs arg-types arg-predicates]} gspec]
    (doseq [[arg-spec human-readable-expected-type {::grp.art/keys [samples original-expression] :as descr} n]
            (map vector arg-specs arg-types actual-argument-type-descriptors (range))
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
           ::grp.art/message             (str "Function argument " (inc n)
                                           (when original-expression (str " (" (pr-str original-expression) ")"))
                                           " failed to pass spec " human-readable-expected-type
                                           ". Expression sample that failed: " (pr-str failing-sample) ".")})))
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
    (validate-argtypes! env sym argtypes)
    {::grp.art/spec    return-spec
     ::grp.art/type    return-type
     ::grp.art/samples (grp.sampler/sample! env fd argtypes)}))
