(ns com.fulcrologic.guardrails-pro.analysis.function-type
  (:require
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]))

(s/def ::destructurable
  (s/or
    :symbol symbol?
    :vector vector?
    :map map?))

;; TODO: check for errors
(>defn destructure! [env bind-sexpr value-type-desc]
  [::grp.art/env ::destructurable ::grp.art/type-description
   => (s/map-of symbol? ::grp.art/type-description)]
  (log/info :destructure! bind-sexpr value-type-desc)
  (letfn [(MAP* [[k v :as entry]]
            (cond
              (and (qualified-keyword? k) (= (name k) "keys"))
              #_=> (mapv (fn [sym]
                           (let [spec-kw (keyword (namespace k) (str sym))]
                             (if-let [spec (s/get-spec spec-kw)]
                               (let [samples (grp.sampler/try-sampling! env (s/gen spec) {::grp.art/original-expression entry})
                                     type    (cond-> {::grp.art/spec                spec
                                                      ::grp.art/original-expression (pr-str sym)
                                                      ::grp.art/type                (pr-str sym)}
                                               samples (assoc ::grp.art/samples (set samples)))]
                                 (grp.art/record-binding! env sym type)
                                 [sym type])
                               (grp.art/record-warning! env sym
                                 :warning/qualified-keyword-missing-spec))))
                     v)
              (qualified-keyword? v)
              #_=> (when-let [spec (s/get-spec v)]
                     (let [samples (grp.sampler/try-sampling! env (s/gen spec) {::grp.art/original-expression entry})
                           type    (cond-> {::grp.art/spec                spec
                                            ::grp.art/original-expression (pr-str k)
                                            ::grp.art/type                (pr-str v)}
                                     samples (assoc ::grp.art/samples (set samples)))]
                       (grp.art/record-binding! env k type)
                       [[k type]]))
              (= :as k)
              #_=> (let [typ (assoc value-type-desc ::grp.art/original-expression v)]
                     (grp.art/record-binding! env v typ)
                     [[v typ]])))]
    (let [typ (assoc value-type-desc ::grp.art/original-expression bind-sexpr)]
      (cond
        (symbol? bind-sexpr) (do
                               (grp.art/record-binding! env bind-sexpr typ)
                               {bind-sexpr typ})
        (vector? bind-sexpr) (let [as-sym (some #(when (= :as (first %))
                                                   (second %))
                                            (partition 2 1 bind-sexpr))]
                               (if as-sym
                                 (do
                                   (grp.art/record-binding! env as-sym typ)
                                   {as-sym typ})
                                 {}))
        ;; TODO: might need to return for :keys [x ...] an empty type desc
        (map? bind-sexpr) (into {}
                            (mapcat MAP*)
                            bind-sexpr)))))

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
          env (destructure! env bind-sexpr
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
           ::grp.art/expected            {::grp.art/spec return-spec ::grp.art/type return-type}
           ::grp.art/problem-type        :error/value-failed-spec})))))

(>defn validate-argtypes!
  [env sym actual-argument-type-descriptors]
  [::grp.art/env qualified-symbol? (s/coll-of ::grp.art/type-description) => any?]
  (let [{::grp.art/keys [arities]} (grp.art/function-detail env sym)
        {::grp.art/keys [gspec]} (grp.art/get-arity arities actual-argument-type-descriptors)
        {::grp.art/keys [arg-specs arg-types arg-predicates]} gspec]
    (doseq [[arg-spec arg-type {::grp.art/keys [samples original-expression] :as descr} n]
            (map vector arg-specs arg-types actual-argument-type-descriptors (range))
            :let [checkable? (and arg-spec (seq samples))]]
      (when-not checkable?
        (grp.art/record-warning! env original-expression :warning/unable-to-check))
      (when-let [{:keys [failing-sample]} (and checkable?
                                            (some (fn _invalid-sample [sample]
                                                    (when-not (s/valid? arg-spec sample)
                                                      {:failing-sample sample}))
                                              samples))]
        (grp.art/record-error! env
          {::grp.art/original-expression original-expression
           ::grp.art/expected            {::grp.art/spec arg-spec ::grp.art/type arg-type}
           ::grp.art/actual              {::grp.art/failing-samples #{failing-sample}}
           ::grp.art/problem-type        :error/function-argument-failed-spec
           ::grp.art/message-params      {:argument-number (inc n)}})))
    (doseq [sample-arguments (apply map vector (map ::grp.art/samples actual-argument-type-descriptors))]
      (doseq [arg-pred arg-predicates
              :when (every? (partial apply s/valid?)
                      (map vector arg-specs sample-arguments))]
        (when-not (apply arg-pred sample-arguments)
          (grp.art/record-error! env
            {::grp.art/original-expression (map ::grp.art/original-expression actual-argument-type-descriptors)
             ::grp.art/actual              {::grp.art/failing-samples (set sample-arguments)}
             ::grp.art/expected            {::grp.art/spec arg-pred}
             ::grp.art/problem-type        :error/function-arguments-failed-predicate
             ::grp.art/message-params      {:function-name sym}})))))
  :done)

(defn calculate-function-type [env sym argtypes]
  (let [{::grp.art/keys [arities] :as fd} (grp.art/function-detail env sym)
        {::grp.art/keys [gspec]} (grp.art/get-arity arities argtypes)
        {::grp.art/keys [return-type return-spec]} gspec]
    (validate-argtypes! env sym argtypes)
    {::grp.art/spec    return-spec
     ::grp.art/type    return-type
     ::grp.art/samples (grp.sampler/sample! env fd argtypes)}))
