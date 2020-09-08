(ns com.fulcrologic.guardrails-pro.static.sampler
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
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
  (log/info "Using default return sample generator")
  return-sample)

(defmethod return-sample-generator ::pure
  [env x {:keys [fn-ref args]}]
  (log/info "Using pure return sample generator")
  (apply fn-ref args))

(defmethod return-sample-generator ::merge-arg
  [env _ {:keys [args return-sample] N :params}]
  (log/info "Using merge-arg return sample generator")
  (merge return-sample (nth args (or N 0) {})))

; NOTE: HOFs (might) need argument type descriptors
;; HOF notes
;(>defn f [m]
;  (let [a (range 1 2)
;        g (>fn [a] [int? => string?])
;        f (comp
;            (>fn [a] [map? => string?])
;            (>fn [a] [(>fspec [n] [int? => int?]) => map?])
;            some-f
;            #_(>fn [a] [int? => (>fspec [x] [number? => number?] string?)]))
;        bb (into #{}
;             (comp
;               (map f) ;; >fspec ...
;               (filter :person/fat?))
;             people)
;        new-seq (map (>fn [x] ^:boo [int? => int?]
;                       (map (fn ...) ...)
;                       m) a)]))

;; NOTE: WIP
(defmethod return-sample-generator ::map-like
  [env x {:keys [fn-ref args argtypes return-sample]}]
  (let [{::grp.art/keys [arities]} (first argtypes)
        {::grp.art/keys [gspec]} (get arities 1)
        {::grp.art/keys [generator return-spec]} gspec]
    (map (fn [_] (gen/generate (or generator (s/gen return-spec))))
      (second args))))

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
     (catch #?(:clj Throwable :cljs :default) _
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
               :merge-arg ::merge-arg}
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

(>defn sample! [env fd argtypes]
  [::grp.art/env (s/keys :req [::grp.art/name ::grp.art/arities ::grp.art/fn-ref]) (s/coll-of ::grp.art/type-description)
   => ::grp.art/samples]
  (let [{::grp.art/keys [name arities fn-ref]} fd
        {::grp.art/keys [gspec]} (grp.art/get-arity arities argtypes)
        {::grp.art/keys [metadata return-spec generator]} gspec
        sampler (-> metadata convert-shorthand-metadata derive-sampler-type)
        generator (try
                    (when (seq argtypes)
                      (gen/fmap (partial return-sample-generator env sampler)
                        (gen/hash-map
                          :args (apply gen/tuple (map gen/elements (map ::grp.art/samples argtypes)))
                          :fn-ref (gen/return fn-ref)
                          :params (gen/return (and sampler (if (vector? sampler) (second sampler) sampler)))
                          :return-sample (or generator (s/gen return-spec)))))
                    (catch #?(:clj  Exception
                              :cljs :default) e
                      (log/error e "Unable to build generator from " [sampler return-spec generator])
                      nil))]
    (if generator
      (try-sampling! env generator {::grp.art/original-expression name})
      #{})))
