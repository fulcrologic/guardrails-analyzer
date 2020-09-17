(ns com.fulcrologic.guardrails-pro.analysis.sampler
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [taoensso.timbre :as log]))

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

(defmethod propagate-samples-mm! ::pure
  [env x {:keys [fn-ref args]}]
  ;; TODO: assert all function argtypes are also pure
  (apply fn-ref args))

(defmethod propagate-samples-mm! ::merge-arg
  [env _ {:keys [args return-sample-fn] N :params}]
  (merge (return-sample-fn) (nth args (or N 0) {})))

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

(declare convert-shorthand-metadata derive-sampler-type)

(>defn get-fn-ref [env {:as fd ::grp.art/keys [fn-ref env->fn]}]
  [::grp.art/env (s/keys :req [(or ::grp.art/fn-ref ::grp.art/env->fn)]) => fn?]
  (or fn-ref (env->fn env)))

(>defn get-gspec [fd argtypes]
  [(s/keys :req [::grp.art/arities]) (s/coll-of ::grp.art/type-description) => ::grp.art/gspec]
  (when-let [gspec (some-> fd ::grp.art/arities (grp.art/get-arity argtypes) ::grp.art/gspec)]
    (assoc gspec ::grp.art/sampler
      (some-> gspec ::grp.art/metadata convert-shorthand-metadata derive-sampler-type))))

(>defn sampler-params [sampler]
  [(? ::sampler) => (? some?)]
  (when (vector? sampler)
    (second sampler)))

(>defn return-sample-gen [{::grp.art/keys [generator return-spec]}]
  [(s/keys :req [(or ::grp.art/generator ::grp.art/return-spec)]) => ::generator]
  (or generator (s/gen return-spec)))

(>defn get-args [{:as td ::grp.art/keys [samples fn-ref]}]
  [::grp.art/type-description => (s/coll-of any? :min-count 1)]
  (or
    (and (seq samples) samples)
    (and fn-ref [fn-ref])
    (throw (ex-info "Failed to get samples or fn-ref for type description"
             {::grp.art/type-description td}))))

(>defn args-gen [env args]
  [::grp.art/env (s/coll-of (s/coll-of any? :min-count 1)) => ::generator]
  (apply gen/tuple (map gen/elements args)))

(>defn params-gen [env fd argtypes]
  [::grp.art/env
   (s/keys :req [(or ::grp.art/fn-ref ::grp.art/env->fn)])
   (s/coll-of ::grp.art/type-description)
   => ::generator]
  (let [{::grp.art/keys [sampler] :as gspec} (get-gspec fd argtypes)]
    (gen/hash-map
      :fn-ref (gen/return (get-fn-ref env fd))
      :params (gen/return (sampler-params sampler))
      :argtypes (gen/return argtypes)
      :return-sample-fn (gen/return #(gen/generate (return-sample-gen gspec))))))

(>defn make-generator [env fd argtypes]
  [::grp.art/env
   (s/keys :req [(or ::grp.art/fn-ref ::grp.art/env->fn)])
   (s/coll-of ::grp.art/type-description)
   => (? ::generator)]
  (let [{::grp.art/keys [sampler] :as gspec} (get-gspec fd argtypes)]
    (gen/let [params (params-gen env fd argtypes)
              args (args-gen env (map get-args argtypes))]
      (propagate-samples! env sampler
        (assoc params :args args)))))

(>defn sample! [env fd argtypes]
  [::grp.art/env
   (s/keys :req [::grp.art/arities
                 (or ::grp.art/fn-ref ::grp.art/env->fn)
                 (or ::grp.art/name ::grp.art/lambda-name)])
   (s/coll-of ::grp.art/type-description)
   => ::grp.art/samples]
  (let [generator (make-generator env fd argtypes)]
    (try-sampling! env generator
      {::grp.art/original-expression
       ((some-fn ::grp.art/name ::grp.art/lambda-name) fd)})))

(defmethod propagate-samples-mm! ::map-like
  [env _ {:keys [return-sample-fn], [function & colls] :argtypes}]
  (let [{::grp.art/keys [sampler] :as gspec} (get-gspec function colls)]
    ;; TODO:
    ;; - transducer if no colls
    ;; - ? pure if function is pure
    ;; - if sampler: assert colls samples are `seq`
    (prn :map-like/colls colls)
    (if-not sampler (return-sample-fn)
      (try-sampling! env
        (gen/let [params (params-gen env function colls)]
          (map #(propagate-samples! env sampler
                  (assoc params :args %))
            (apply mapcat (partial map vector)
              (map get-args colls))))
        {::grp.art/original-expression
         ((some-fn ::grp.art/name ::grp.art/lambda-name) function)}))))

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

(defn random-sample-fn [{::grp.art/keys [samples]}]
  (fn [& args] (rand-nth (vec samples))))
