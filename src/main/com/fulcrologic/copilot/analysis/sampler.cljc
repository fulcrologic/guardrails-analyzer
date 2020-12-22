(ns com.fulcrologic.copilot.analysis.sampler
  #?(:cljs (:require-macros [com.fulcrologic.copilot.analysis.sampler]))
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.analysis.spec :as cp.spec]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defmulti propagate-samples-mm!
  (fn [env x params]
    (cond
      (keyword? x) x
      (vector? x) (first x)
      :else :default)))

(defn propagate-samples! [env strategy params]
  (log/trace :propagate-samples!/strategy strategy)
  (log/spy :trace :propagate-samples!/return
    (propagate-samples-mm! env strategy params)))

(defmethod propagate-samples-mm! :default
  [env x {:keys [return-sample-fn]}]
  (return-sample-fn))

(defmethod propagate-samples-mm! ::merge-arg
  [env _ {:keys [args return-sample-fn] N :params}]
  (merge (return-sample-fn) (nth args (or N 0) {})))

(defn ->samples [x] (with-meta x {::samples? true}))

(defn flatten-samples [x]
  (letfn [(samples? [x] (and (set? x) (some-> x meta ::samples?)))]
    (set (mapcat #(if (samples? %) % [%]) x))))
(>defn try-sampling!
  ([env gen] [::cp.art/env (? ::cp.art/generator) => ::cp.art/samples]
   (try-sampling! env gen {}))
  ([env gen extra]
   [::cp.art/env (? ::cp.art/generator) map? => ::cp.art/samples]
   (try
     (if-not gen (throw (ex-info "No generator provided!" {::silent? true}))
       (if-let [samples (seq (cp.spec/sample env gen))]
         (->samples (flatten-samples samples))
         (throw (ex-info "Generator returned no samples!" {:gen gen}))))
     (catch #?(:clj Throwable :cljs :default) e
       (log/error (when-not (::silent? (ex-data e)) e) "Failed to generate samples!")
       (cp.art/record-error! env
         (merge {::cp.art/problem-type :error/sample-generator-failed}
           extra))
       #{}))))

(declare convert-shorthand-metadata derive-sampler-type)

(>defn get-fn-ref [env {:as fd ::cp.art/keys [fn-ref env->fn]}]
  [::cp.art/env (s/keys :req [(or ::cp.art/fn-ref ::cp.art/env->fn)]) => fn?]
  (or fn-ref (env->fn env)))

(>defn get-gspec [fd argtypes]
  [(s/keys :req [::cp.art/arities]) (s/coll-of any?) => ::cp.art/gspec]
  (let [gspec (-> fd ::cp.art/arities (cp.art/get-arity argtypes) ::cp.art/gspec)
        sampler (some-> gspec ::cp.art/metadata convert-shorthand-metadata derive-sampler-type)]
    (cond-> gspec
      sampler (assoc ::cp.art/sampler sampler))))

(>defn sampler-params [sampler]
  [(? ::sampler) => (? some?)]
  (when (vector? sampler)
    (second sampler)))

(>defn return-sample-gen [env {:as fd ::cp.art/keys [generator return-spec return-type]}]
  [::cp.art/env (s/keys :req [(or ::cp.art/generator ::cp.art/return-spec)]) => ::cp.art/generator]
  (try (or generator (cp.spec/generator env return-spec))
    (catch #? (:clj Exception :cljs :default) e
      (log/error e "Could not create generator for" return-type)
      nil)))

(>defn get-args [env {:as td ::cp.art/keys [samples fn-ref env->fn]}]
  [::cp.art/env ::cp.art/type-description => (s/coll-of any? :min-count 1)]
  (or
    (and (seq samples) samples)
    (and fn-ref [fn-ref])
    (and env->fn [(env->fn env)])
    (throw (ex-info "Failed to get samples or fn-ref for type description"
             {::cp.art/type-description td}))))

(>defn args-gen [env args]
  [::cp.art/env (s/coll-of (s/coll-of any? :min-count 1)) => ::cp.art/generator]
  (apply gen/tuple (map gen/elements args)))

(>defn params-gen [env fd argtypes]
  [::cp.art/env
   (s/keys :req [(or ::cp.art/fn-ref ::cp.art/env->fn)])
   (s/coll-of ::cp.art/type-description)
   => ::cp.art/generator]
  (let [{::cp.art/keys [sampler] :as gspec} (get-gspec fd argtypes)]
    (gen/hash-map
      :fn-ref (gen/return (get-fn-ref env fd))
      :params (gen/return (sampler-params sampler))
      :argtypes (gen/return argtypes)
      :return-sample-fn (gen/return #(cp.spec/generate env (return-sample-gen env gspec))))))

(>defn sample! [env fd argtypes]
  [::cp.art/env
   (s/keys :req [::cp.art/arities
                 (or ::cp.art/fn-ref ::cp.art/env->fn)
                 (or ::cp.art/fn-name ::cp.art/var-name ::cp.art/lambda-name
                   ::cp.art/original-expression)])
   (s/coll-of ::cp.art/type-description)
   => ::cp.art/samples]
  (let [{::cp.art/keys [sampler] :as gspec} (get-gspec fd argtypes)
        generator (gen/let [params (params-gen env fd argtypes)
                            args (args-gen env (map (partial get-args env) argtypes))]
                    (propagate-samples! env sampler
                      (assoc params :args args)))]
    (try-sampling! env generator
      {::cp.art/original-expression
       (or ((some-fn ::cp.art/fn-name ::cp.art/var-name ::cp.art/lambda-name) fd)
         (::cp.art/original-expression fd))})))

(defmethod propagate-samples-mm! ::pure
  [env x {:keys [fn-ref args argtypes]}]
  (apply fn-ref
    (map (fn [arg ?fn-td]
           (if-not (::cp.art/arities ?fn-td)
             arg
             (fn [& args]
               (let [function arg
                     gspec (get-gspec ?fn-td args)]
                 (if (= ::pure (::cp.art/sampler gspec))
                   ;; TODO: what if throws?
                   (apply function args)
                   (->> gspec
                     (return-sample-gen env)
                     (cp.spec/generate env)))))))
      args argtypes)))

(defn map-like-args [env colls]
  (let [coll-args (map (partial get-args env) colls)]
    (assert (every? (partial every? seqable?) coll-args)
      "map expects all sequence arguments to be sequences")
    (apply mapcat (partial map vector) coll-args)))

(defmethod propagate-samples-mm! ::map-like
  [env _ {:keys [return-sample-fn], [function & colls] :argtypes}]
  (let [{::cp.art/keys [sampler] :as gspec} (get-gspec function colls)]
    (try-sampling! env
      (if-not sampler
        (return-sample-gen env gspec)
        (gen/let [params (params-gen env function colls)]
          (map #(propagate-samples! env sampler
                  (assoc params :args %))
            (map-like-args env colls))))
      {::cp.art/original-expression
       ((some-fn ::cp.art/name ::cp.art/lambda-name) function)})))

(s/def ::dispatch keyword?)
(s/def ::sampler (s/or
                   :kw ::dispatch
                   :vec (s/and vector? #(s/valid? ::dispatch (first %)))))

(>defn convert-shorthand-metadata [m] [map? => map?]
  (let [remap {:pure      ::pure
               :pure?     ::pure
               :merge-arg ::merge-arg
               :map-like  ::map-like}
        rename-key (fn [disp]
                     (cond->> disp
                       (contains? remap disp)
                       (get remap)))]
    (-> (set/rename-keys m remap)
      (cond-> (::sampler m)
        (update ::sampler
          #(if (vector? %)
             (update % 0 rename-key)
             (rename-key %)))))))

(>defn derive-sampler-type [m] [map? => (? ::sampler)]
  (if-let [sampler (get m ::sampler)]
    sampler
    (let [valid-samplers (set (keys (methods propagate-samples-mm!)))
          possible-values (keep (fn [[k v]]
                                  (when (and (valid-samplers k) (true? v)) k))
                            m)]
      (when (< 1 (count possible-values))
        (log/warn "Multiple possible type propagation candidates for spec list"
          (seq possible-values)))
      (first possible-values))))

(>defn random-samples-from [env & tds]
  [::cp.art/env (s/+ ::cp.art/type-description) => ::cp.art/samples]
  (into #{}
    (some->> tds
      (remove ::cp.art/unknown-expression)
      (keep (fn [td]
              (when-let [samples (seq (::cp.art/samples td))]
                (gen/elements samples))))
      (seq) (gen/one-of)
      (cp.spec/sample env))))

(defn random-samples-from-each [env tds]
  (into #{}
    (some->> tds
      (remove ::cp.art/unknown-expression)
      (keep (fn [td]
              (when-let [samples (seq (::cp.art/samples td))]
                (gen/elements samples))))
      (seq) (apply gen/tuple)
      (cp.spec/sample env))))
