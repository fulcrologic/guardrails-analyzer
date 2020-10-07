(ns com.fulcrologic.guardrails-pro.analysis.function-type
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails.core :refer [>defn >defn- >fspec =>]]
    [taoensso.timbre :as log]))

(s/def ::destructurable
  (s/or
    :symbol symbol?
    :vector vector?
    :map map?))

(declare -destructure!)

(>defn- ?validate-samples! [env kw samples & [orig-expr]]
  [::grp.art/env qualified-keyword? ::grp.art/samples
   (s/cat :orig-expr (s/? ::grp.art/original-expression)) => any?]
  (if-let [spec (grp.spec/lookup env kw)]
    (do
      (when-not (some #(contains? % kw) samples)
        (grp.art/record-warning! env kw :warning/failed-to-find-keyword-in-hashmap-samples))
      (when-let [failing-case (some #(when-not (grp.spec/valid? env spec %) %) samples)]
        (grp.art/record-error! env
          #::grp.art{:problem-type :error/value-failed-spec
                     :expected #::grp.art{:spec spec :type (pr-str kw)}
                     :actual failing-case
                     :original-expression (or orig-expr kw)})))
    (grp.art/record-warning! env kw
      :warning/qualified-keyword-missing-spec)))

(>defn destr-map-entry! [env [k v] td]
  [::grp.art/env map-entry? ::grp.art/type-description
   => (s/coll-of (s/tuple symbol? ::grp.art/type-description))]
  (cond
    (= :keys k) {}
    (and (qualified-keyword? k) (= (name k) "keys"))
    #_=> (mapv (fn [sym]
                 (let [spec-kw (keyword (namespace k) (str sym))]
                   (?validate-samples! env spec-kw (::grp.art/samples td) sym)
                   [sym {::grp.art/original-expression sym
                         ::grp.art/samples (set (map spec-kw (::grp.art/samples td)))
                         ::grp.art/spec spec-kw
                         ::grp.art/type (pr-str spec-kw)}]))
           v)
    (qualified-keyword? v)
    #_=> (do
           (?validate-samples! env v (::grp.art/samples td))
           (-destructure! env k
             {::grp.art/original-expression k
              ;; get from `td` samples
              ::grp.art/samples (set (map v (::grp.art/samples td)))
              ::grp.art/spec v
              ::grp.art/type (pr-str v)}))
    (= :as k)
    #_=> [[v (assoc td ::grp.art/original-expression v)]]
    :else []))

(>defn destr-vector! [env vect td]
  [::grp.art/env vector? ::grp.art/type-description
   => (s/coll-of (s/tuple symbol? ::grp.art/type-description))]
  (let [[symbols specials] (split-with (complement #{'& :as}) vect)
        sym-count (count symbols)
        coll-bindings (into {}
                        (map (fn [[expr bind]]
                               [bind
                                (case expr
                                  :as td
                                  '&  {::grp.art/samples
                                       (set (map (partial drop sym-count)
                                              (::grp.art/samples td)))})]))
                        (partition 2 specials))]
    (into coll-bindings
      (mapcat
        (fn [i sym]
          (-destructure! env sym
            {::grp.art/samples
             (set (map #(nth % i nil)
                    (::grp.art/samples td)))}))
        (range)
        symbols))))

(>defn -destructure! [env bind-sexpr value-type-desc]
  [::grp.art/env ::destructurable ::grp.art/type-description
   => (s/map-of symbol? ::grp.art/type-description)]
  (let [typ (assoc value-type-desc ::grp.art/original-expression bind-sexpr)]
    (cond
      (symbol? bind-sexpr) {bind-sexpr typ}
      (vector? bind-sexpr) (destr-vector! env bind-sexpr typ)
      (map? bind-sexpr)
      (into {}
        (mapcat #(destr-map-entry! env % value-type-desc))
        bind-sexpr))))

(>defn destructure! [env bind-sexpr value-type-desc]
  [::grp.art/env ::destructurable ::grp.art/type-description
   => (s/map-of symbol? ::grp.art/type-description)]
  (let [bindings (-destructure! env bind-sexpr value-type-desc) ]
    (doseq [[sym td] bindings]
      (grp.art/record-binding! env sym td))
    bindings))

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
           ::grp.art/expected            #::grp.art{:spec return-spec :type return-type}
           ::grp.art/problem-type        :error/value-failed-spec})))))

(>defn validate-argtypes!?
  [env {::grp.art/keys [arglist gspec]} argtypes]
  [::grp.art/env ::grp.art/arity-detail (s/coll-of ::grp.art/type-description) => boolean?]
  (let [failed? (atom false)
        {::grp.art/keys [argument-types argument-specs argument-predicates]} gspec]
    (when-not (or (some #{'&} arglist)
                (= (count arglist) (count argtypes)))
      (reset! failed? true)
      (grp.art/record-error! env
        #::grp.art{:original-expression (map ::grp.art/original-expression argtypes)
                   :problem-type        :error/invalid-function-arguments-count}))
    (doseq [:when (not @failed?)
            [arg-idx argument-type argument-spec {::grp.art/keys [samples original-expression]}]
            (map vector (range) argument-types argument-specs argtypes)
            :let [checkable? (and argument-spec (seq samples))]]
      (when-not checkable?
        (grp.art/record-warning! env original-expression :warning/unable-to-check))
      (when-let [{:keys [failing-sample]}
                 (and checkable?
                   (some (fn _invalid-sample [sample]
                           (when-not (grp.spec/valid? env argument-spec sample)
                             {:failing-sample sample}))
                     samples))]
        (reset! failed? true)
        (grp.art/record-error! env
          {::grp.art/original-expression original-expression
           ::grp.art/expected            #::grp.art{:spec argument-spec :type argument-type}
           ::grp.art/actual              {::grp.art/failing-samples #{failing-sample}}
           ::grp.art/problem-type        :error/function-argument-failed-spec
           ::grp.art/message-params      {:argument-number (inc arg-idx)}})))
    (doseq [:when (not @failed?)
            sample-arguments (apply map vector (map ::grp.art/samples argtypes))
            argument-pred argument-predicates
            :when (not (apply argument-pred sample-arguments))]
      (reset! failed? true)
      (grp.art/record-error! env
        {::grp.art/original-expression (map ::grp.art/original-expression argtypes)
         ::grp.art/actual              {::grp.art/failing-samples (set sample-arguments)}
         ::grp.art/expected            {::grp.art/spec argument-pred}
         ::grp.art/problem-type        :error/function-arguments-failed-predicate}))
    (not @failed?)))

(>defn calculate-function-type [env function argtypes]
  [::grp.art/env ::grp.art/function (s/coll-of ::grp.art/type-description) => any?]
  (let [{::grp.art/keys [arities]} function
        {::grp.art/keys [gspec] :as arity} (grp.art/get-arity arities argtypes)
        {::grp.art/keys [return-type return-spec]} gspec]
    ;; TODO: if not valid: sample return-spec
    (cond-> #::grp.art{:type return-type :spec return-spec}
      (validate-argtypes!? env arity argtypes)
      (assoc ::grp.art/samples (grp.sampler/sample! env function argtypes)))))
