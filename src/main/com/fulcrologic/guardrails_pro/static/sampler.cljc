(ns com.fulcrologic.guardrails-pro.static.sampler
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

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

(defmethod return-sample-generator :pure
  [env x {:keys [fn-ref args]}]
  (log/info "Using pure return sample generator")
  (apply fn-ref args))

(defmethod return-sample-generator :merge-arg
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
;; GAME PLAN:
;; (>defn incr ...)
;; (map incr (range 10))
;; NOTE: WIP
(defmethod return-sample-generator :map-like
  [env x {:keys [fn-ref args argtypes return-sample]}]
  (let [{::grp.art/keys [arities]} (first argtypes)
        {::grp.art/keys [gspec]} (get arities 1)
        {::grp.art/keys [generator return-spec]} gspec]
    (map (fn [_] (gen/generate (or generator (s/gen return-spec))))
      (second args))))

(s/def ::generator gen/generator?)

(>defn try-sampling!
  ([env gen] [::grp.art/env ::generator => (? ::grp.art/samples)]
   (try-sampling! env gen {}))
  ([env gen extra]
   [::grp.art/env ::generator map? => (? ::grp.art/samples)]
   (try
     (if-let [samples (seq (gen/sample gen))]
       (set samples)
       (throw (ex-info "No samples!?" {})))
     (catch #?(:clj Throwable :cljs :default) _
       (grp.art/record-error! env
         (merge {::grp.art/message (str "Failed to generate samples!")}
           extra))
       #{}))))

(>defn sample! [env fd argtypes]
  [::grp.art/env (s/keys :req [::grp.art/name ::grp.art/arities ::grp.art/fn-ref]) (s/coll-of ::grp.art/type-description)
   => ::grp.art/samples]
  (let [{::grp.art/keys [name arities fn-ref]} fd
        {::grp.art/keys [gspec]} (grp.art/get-arity arities argtypes)
        {::grp.art/keys [sampler return-spec generator]} gspec
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

#_(>defn hashmap-permutation-generator [sample-map]
  [(s/map-of any? ::grp.art/samples) => ::generator]
  (->> sample-map
    (enc/map-vals gen/elements)
    (apply concat)
    (apply gen/hash-map)))
