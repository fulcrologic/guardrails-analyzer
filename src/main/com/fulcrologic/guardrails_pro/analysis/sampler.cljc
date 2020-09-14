(ns com.fulcrologic.guardrails-pro.analysis.sampler
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

;; TASK: Consider if this should be called `propagate-data`, or `data-propagation-strategy`, etc.
(defmulti return-sample-generator
  (fn [env x params]
    (cond
      (keyword? x) x
      (vector? x) (first x)
      :else :default)))

(defmethod return-sample-generator :default
  [env x {:keys [return-sample]}]
  return-sample)

(defmethod return-sample-generator ::pure
  [env x {:keys [fn-ref args]}]
  (apply fn-ref args))

(defmethod return-sample-generator ::merge-arg
  [env _ {:keys [args return-sample] N :params}]
  (merge return-sample (nth args (or N 0) {})))

(s/def ::generator gen/generator?)

(>defn try-sampling!
  ([env gen] [::grp.art/env ::generator => ::grp.art/samples]
   (try-sampling! env gen {}))
  ([env gen extra]
   [::grp.art/env ::generator map? => ::grp.art/samples]
   (try
     (if-let [samples (seq (gen/sample gen))]
       (set samples)
       (throw (ex-info "No samples!?" {})))
     (catch #?(:clj Throwable :cljs :default) e
       (log/error e "Failed to generate samples!")
       (grp.art/record-error! env
         (merge {::grp.art/message (str "Failed to generate samples!")}
           extra))
       #{}))))

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
    (let [valid-samplers (set (keys (methods return-sample-generator)))
          possible-values (keep (fn [[k v]]
                                  (when (and (valid-samplers k) (true? v)) k))
                            m)]
      (when (< 1 (count possible-values))
        (log/warn "Multiple possible type propagation candidates for spec list"
          (seq possible-values)))
      (first possible-values))))

(defn get-samples [argtype]
  (cond
    (::grp.art/lambda-name argtype) [(::grp.art/env->fn {})]
    :else (::grp.art/samples argtype)))

(defn get-fn-ref [env {::grp.art/keys [fn-ref env->fn]}]
  (or fn-ref (env->fn env)))

(defn get-gspec [fd argtypes]
  (when-let [gspec (some-> fd ::grp.art/arities (grp.art/get-arity argtypes) ::grp.art/gspec)]
    (prn :gspec gspec)
    (assoc gspec ::grp.art/sampler
      (some-> gspec ::grp.art/metadata convert-shorthand-metadata derive-sampler-type))))

(defn make-generator [env fd argtypes]
  (try
    (let [{::grp.art/keys [sampler generator return-spec] :as gspec} (get-gspec fd argtypes)]
      (when (seq argtypes)
        (gen/fmap (partial return-sample-generator env sampler)
          (gen/hash-map
            :args (apply gen/tuple (map gen/elements (mapv get-samples argtypes)))
            :fn-ref (gen/return (get-fn-ref env fd))
            :params (gen/return (and sampler (if (vector? sampler) (second sampler) sampler)))
            :argtypes (gen/return argtypes)
            :return-sample (or generator (s/gen return-spec))))))
    (catch #?(:clj  Throwable :cljs :default) e
      (log/error e "Unable to create generator for" ((some-fn ::grp.art/name ::grp.art/lambda-name) fd)
        "from" {:fd fd :argtypes argtypes})
      nil)))

(>defn sample! [env fd argtypes]
  [::grp.art/env
   (s/keys :req [::grp.art/arities
                 (or ::grp.art/fn-ref ::grp.art/env->fn)
                 (or ::grp.art/name ::grp.art/lambda-name)])
   (s/coll-of ::grp.art/type-description)
   => ::grp.art/samples]
  (if-let [generator (make-generator env fd argtypes)]
    (try-sampling! env generator
      {::grp.art/original-expression
       ((some-fn ::grp.art/name ::grp.art/lambda-name) fd)})
    #{}))

(defmethod return-sample-generator ::map-like
  [env _ {:keys [args return-sample], [function & colls] :argtypes}]
  (let [{::grp.art/keys [sampler return-spec generator]} (get-gspec function colls)]
    ;; TODO: transducer if no colls
    ;; TASK: if sampler: assert colls samples are `seq`
    (if-not sampler return-sample
      (gen/generate
        (make-generator env function
          (map #(update % ::grp.art/samples (comp set (partial apply concat)))
            colls))))))
