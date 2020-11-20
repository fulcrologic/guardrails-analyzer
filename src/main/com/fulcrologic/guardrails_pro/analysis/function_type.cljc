(ns com.fulcrologic.guardrails-pro.analysis.function-type
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.destructuring :as grp.destr]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn => ?]]))

(>defn interpret-gspec [env arglist gspec]
  [::grp.art/env ::grp.art/arglist (s/coll-of ::grp.art/form :kind vector?) => ::grp.art/gspec]
  (let [[argument-specs gspec] (split-with (complement #{:st '| `gr/| :ret '=> `gr/=>}) gspec)
        [argument-predicates gspec] (split-with (complement #{:ret '=> `gr/=>}) gspec)
        [return-spec gspec] (split-with (complement #{:st '| `gr/|}) gspec)
        [return-predicates generator] (split-with (complement #{:gen '<- `gr/<-}) gspec)]
    ;; ? TODO: generator
    #::grp.art{:argument-specs      (mapv (partial grp.art/lookup-spec env) argument-specs)
               :argument-types      (mapv pr-str argument-specs)
               :argument-predicates (vec (rest argument-predicates))
               :return-spec         (grp.art/lookup-spec env (second return-spec))
               :return-type         (pr-str (second return-spec))
               :return-predicates   (vec (rest return-predicates))}))

(>defn bind-type-desc
  [env typename clojure-spec err]
  [::grp.art/env ::grp.art/type ::grp.art/spec map? => ::grp.art/type-description]
  (let [samples (grp.sampler/try-sampling! env (grp.spec/generator env clojure-spec) err)]
    (cond-> {::grp.art/spec clojure-spec
             ::grp.art/type typename}
      (seq samples) (assoc ::grp.art/samples samples))))

(>defn bind-argument-types
  [env arglist gspec]
  [::grp.art/env (s/coll-of symbol? :kind vector?) ::grp.art/gspec => ::grp.art/env]
  (let [{::grp.art/keys [argument-types argument-specs]} gspec]
    (reduce
      (fn [env [bind-sexpr argument-type argument-spec]]
        (reduce-kv grp.art/remember-local
          env (grp.destr/destructure! env bind-sexpr
                (bind-type-desc env argument-type argument-spec
                  {::grp.art/original-expression arglist}))))
      env
      (map vector arglist argument-types argument-specs))))

(>defn check-return-type!
  [env {::grp.art/keys [return-type return-spec]} {::grp.art/keys [samples]} expr loc]
  [::grp.art/env ::grp.art/gspec ::grp.art/type-description ::grp.art/original-expression (? map?) => any?]
  (let [sample-failure (some #(when-not (grp.spec/valid? env return-spec %)
                                {:failing-case %})
                         samples)]
    (when (contains? sample-failure :failing-case)
      (let [sample-failure (:failing-case sample-failure)]
        (grp.art/record-error! (grp.art/update-location env loc)
          {::grp.art/original-expression expr
           ::grp.art/actual              {::grp.art/failing-samples #{sample-failure}}
           ::grp.art/expected            #::grp.art{:spec return-spec :type return-type}
           ::grp.art/problem-type        :error/value-failed-spec})))))

(>defn validate-argtypes!?
  [env {::grp.art/keys [arglist gspec]} argtypes]
  [::grp.art/env ::grp.art/arity-detail (s/coll-of ::grp.art/type-description) => boolean?]
  (if (some ::grp.art/unknown-expression argtypes)
    false                                                   ;; TASK: tests
    (let [failed?     (atom false)
          get-samples (comp set (partial grp.sampler/get-args env))
          {::grp.art/keys [argument-types argument-specs argument-predicates]} gspec]
      (when-not (or (some #{'&} arglist)
                  (= (count arglist) (count argtypes)))
        (reset! failed? true)
        (grp.art/record-error! env
          #::grp.art{:original-expression (map ::grp.art/original-expression argtypes)
                     :problem-type        :error/invalid-function-arguments-count}))
      (when-not @failed?
        (let [[syms specials] (split-with (complement #{:as '&}) arglist)]
          (doseq [[arg-sym argument-type argument-spec [samples original-expression]]
                  (map vector syms argument-types argument-specs
                    (map (juxt get-samples ::grp.art/original-expression) argtypes))
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
                 ::grp.art/message-params      {:argument arg-sym}})))
          (doseq [:when (some #{'&} specials)
                  :let [rest-argtypes (seq (drop (count syms) argtypes))]
                  :when (seq rest-argtypes)
                  :let [rst-sym   (get (apply hash-map specials) '& nil)
                        args-spec (first (drop (count syms) argument-specs))
                        args-type (first (drop (count syms) argument-types))]
                  sample-rest-arguments (apply map vector (map get-samples rest-argtypes))
                  :when (not (grp.spec/valid? env args-spec sample-rest-arguments))]
            (reset! failed? true)
            (grp.art/record-error! env
              #::grp.art{:original-expression (map ::grp.art/original-expression rest-argtypes)
                         :actual              {::grp.art/failing-samples #{sample-rest-arguments}}
                         :expected            #::grp.art{:spec args-spec :type args-type}
                         :problem-type        :error/function-arguments-failed-spec}))))
      (when (not @failed?)
        (doseq [sample-arguments (apply map vector (map get-samples argtypes))
                argument-pred    argument-predicates]
          (when-not (apply argument-pred sample-arguments)
            (reset! failed? true)
            (grp.art/record-error! env
              {::grp.art/original-expression (map ::grp.art/original-expression argtypes)
               ::grp.art/actual              {::grp.art/failing-samples #{sample-arguments}}
               ::grp.art/expected            {::grp.art/spec argument-pred}
               ::grp.art/problem-type        :error/function-arguments-failed-predicate}))))
      (not @failed?))))

(defn valid-argtypes? [env arity argtypes]
  (with-redefs [grp.art/record-error! (constantly nil)]
    (validate-argtypes!? env arity argtypes)))

(s/def ::partial-argtypes (s/coll-of ::grp.art/type-description))

(>defn analyze-function-call! [env function argtypes]
  [::grp.art/env (s/or :function ::grp.art/function :lambda ::grp.art/lambda)
   (s/coll-of ::grp.art/type-description)
   => ::grp.art/type-description]
  (let [{::grp.art/keys [arities] ::keys [partial-argtypes]} function
        argtypes      (concat partial-argtypes argtypes)
        {::grp.art/keys [gspec] :as arity} (grp.art/get-arity arities argtypes)
        {::grp.art/keys [return-type return-spec]} gspec
        function-type #::grp.art{:type return-type :spec return-spec}]
    (assoc function-type ::grp.art/samples
                         (if (validate-argtypes!? env arity argtypes)
                           (grp.sampler/sample! env function argtypes)
                           (grp.sampler/try-sampling! env (grp.spec/generator env return-spec)
                             {::grp.art/original-expression function})))))

(>defn sample->type-description [sample]
  [any? => ::grp.art/type-description]
  {::grp.art/samples             #{sample}
   ::grp.art/original-expression sample})

(>defn validate-arguments-predicate!? [env arity & args]
  [::grp.art/env ::grp.art/arity-detail (s/+ any?) => boolean?]
  (validate-argtypes!? env arity
    (map sample->type-description args)))
