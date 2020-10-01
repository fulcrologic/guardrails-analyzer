(ns com.fulcrologic.guardrails-pro.analysis.function-type
  (:require
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]))

(s/def ::destructurable
  (s/or
    :symbol symbol?
    :vector vector?
    :map map?))

(>defn destructure! [env bind-sexpr value-type-desc]
  [::grp.art/env ::destructurable ::grp.art/type-description
   => (s/map-of symbol? ::grp.art/type-description)]
  (log/info :destructure! bind-sexpr value-type-desc)
  (letfn [(MAP* [[k v :as entry]]
            (cond
              (and (qualified-keyword? k) (= (name k) "keys"))
              #_=> (mapv (fn [sym]
                           (let [spec-kw (keyword (namespace k) (str sym))]
                             (if-let [spec (grp.spec/lookup env spec-kw)]
                               (let [samples (grp.sampler/try-sampling! env (grp.spec/generator env spec) {::grp.art/original-expression entry})
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
              #_=> (when-let [spec (grp.spec/lookup env v)]
                     (let [samples (grp.sampler/try-sampling! env (grp.spec/generator env spec) {::grp.art/original-expression entry})
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
        (map? bind-sexpr) (into {} (mapcat MAP*) bind-sexpr)))))

(>defn interpret-gspec [env arglist gspec]
  [::grp.art/env ::grp.art/arglist (s/coll-of ::grp.art/form :kind vector?) => ::grp.art/gspec]
  (let [[argument-specs      gspec]     (split-with (complement #{:st '| :ret '=>}) gspec)
        [argument-predicates gspec]     (split-with (complement #{:ret '=>}) gspec)
        [return-spec         gspec]     (split-with (complement #{:st '|})   gspec)
        [return-predicates   generator] (split-with (complement #{:gen '<-}) gspec)]
    #::grp.art{:argument-specs      (mapv (partial grp.art/lookup-spec env) argument-specs)
               :argument-types      (mapv pr-str argument-specs)
               :argument-predicates (some-> (next argument-predicates) vec)
               :return-spec         (grp.art/lookup-spec env (second return-spec))
               :return-type         (pr-str (second return-spec))
               :return-predicates   (some-> (next return-predicates) vec)}))

(>defn bind-type-desc
  [env typename clojure-spec err]
  [::grp.art/env ::grp.art/type ::grp.art/spec map? => ::grp.art/type-description]
  (log/debug :bind-type-desc typename clojure-spec)
  (log/spy :debug :bind-type-desc/return
    (let [samples (grp.sampler/try-sampling! env (grp.spec/generator env clojure-spec) err)]
      (cond-> {::grp.art/spec clojure-spec
               ::grp.art/type typename}
        (seq samples) (assoc ::grp.art/samples samples)))))

(>defn bind-argument-types
  [env arglist gspec]
  [::grp.art/env (s/coll-of symbol? :kind vector?) ::grp.art/gspec => ::grp.art/env]
  (let [{::grp.art/keys [argument-types argument-specs]} gspec]
    (log/warn arglist gspec)
    (reduce
      (fn [env [bind-sexpr argument-type argument-spec]]
        (reduce-kv grp.art/remember-local
          env (destructure! env bind-sexpr
                (bind-type-desc env argument-type argument-spec
                  {::grp.art/original-expression arglist}))))
      env
      (map vector arglist argument-types argument-specs))))

(>defn check-return-type!
  [env {::grp.art/keys [return-type return-spec]} {::grp.art/keys [samples]} expr]
  [::grp.art/env ::grp.art/gspec ::grp.art/type-description ::grp.art/original-expression => any?]
  (let [sample-failure (some #(when-not (grp.spec/valid? env return-spec %)
                                {:failing-case %})
                         samples)]
    (when (contains? sample-failure :failing-case)
      (let [sample-failure (:failing-case sample-failure)]
        (grp.art/record-error! env
          {::grp.art/original-expression expr
           ::grp.art/actual              {::grp.art/failing-samples #{sample-failure}}
           ::grp.art/expected            {::grp.art/spec return-spec ::grp.art/type return-type}
           ::grp.art/problem-type        :error/value-failed-spec})))))

(>defn validate-argtypes!
  [env sym actual-argument-type-descriptors]
  [::grp.art/env qualified-symbol? (s/coll-of ::grp.art/type-description) => any?]
  (let [{::grp.art/keys [arities]} (grp.art/function-detail env sym)
        {::grp.art/keys [gspec]} (grp.art/get-arity arities actual-argument-type-descriptors)
        {::grp.art/keys [argument-types argument-specs argument-predicates]} gspec]
    (doseq [[argument-type argument-spec {::grp.art/keys [samples original-expression] :as descr} n]
            (map vector argument-types argument-specs actual-argument-type-descriptors (range))
            :let [checkable? (and argument-spec (seq samples))]]
      (when-not checkable?
        (grp.art/record-warning! env original-expression :warning/unable-to-check))
      (when-let [{:keys [failing-sample]}
                 (and checkable?
                   (some (fn _invalid-sample [sample]
                           (when-not (grp.spec/valid? env argument-spec sample)
                             {:failing-sample sample}))
                     samples))]
        (grp.art/record-error! env
          {::grp.art/original-expression original-expression
           ::grp.art/expected            {::grp.art/spec argument-spec ::grp.art/type argument-type}
           ::grp.art/actual              {::grp.art/failing-samples #{failing-sample}}
           ::grp.art/problem-type        :error/function-argument-failed-spec
           ::grp.art/message-params      {:argument-number (inc n)}})))
    (doseq [sample-arguments (apply map vector (map ::grp.art/samples actual-argument-type-descriptors))]
      (doseq [argument-pred argument-predicates
              :when (every? (partial apply (partial grp.spec/valid? env))
                      (map vector argument-specs sample-arguments))]
        (when-not (apply argument-pred sample-arguments)
          (grp.art/record-error! env
            {::grp.art/original-expression (map ::grp.art/original-expression actual-argument-type-descriptors)
             ::grp.art/actual              {::grp.art/failing-samples (set sample-arguments)}
             ::grp.art/expected            {::grp.art/spec argument-pred}
             ::grp.art/problem-type        :error/function-arguments-failed-predicate
             ::grp.art/message-params      {:function-name sym}})))))
  :done)

(>defn calculate-function-type [env sym argtypes]
  [::grp.art/env qualified-symbol? (s/coll-of ::grp.art/type-description) => any?]
  (let [{::grp.art/keys [arities] :as fd} (grp.art/function-detail env sym)
        {::grp.art/keys [gspec]} (grp.art/get-arity arities argtypes)
        [return-type return-spec] (::grp.art/return-spec gspec)]
    (validate-argtypes! env sym argtypes)
    {::grp.art/spec    return-spec
     ::grp.art/type    return-type
     ::grp.art/samples (grp.sampler/sample! env fd argtypes)}))
