(ns com.fulcrologic.guardrails-pro.static.sampler
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

;(>defn add-last-name [p]
;  ^{::grp.art/sampler :merge-arg2}
;  [db (s/keys :req [id]) | #(...) => (s/keys :req [full-name]) | ...]
;  [db person]
;  (let [person (db/lookup id)]
;    (assoc person ...)))

(defmulti return-sample-generator
  (fn [env x params]
    (cond
      (keyword? x) x
      (vector? x) (first x)
      :else :default)))

(defmethod return-sample-generator :pure
  [env x {:keys [fn-ref args return-sample params]}]
  (apply fn-ref args))

(defmethod return-sample-generator :default
  [env x {:keys [fn-ref args return-sample params]}]
  return-sample)

;(defmethod rv-generator :merge-arg1 (fn [_ _ return-sample & args] (let [arg1 (first args)] (merge return-sample arg1))))

;(defmethod rv-generator :map-like (fn [_ _ return-sample & args] (let [arg1 (first args)] (merge return-sample arg1))))

(s/def ::generator
  (s/or
    :spec #(s/spec? %)
    :gen (s/keys :req-un [::gen])))

(>defn try-sampling!
  ([env gen] [::grp.art/env ::generator => (? ::grp.art/samples)]
   (try-sampling! env gen {}))
  ([env gen extra]
   [::grp.art/env ::generator map? => (? ::grp.art/samples)]
   (try
     (gen/sample gen)
     (catch #?(:clj Throwable :cljs :default) t
       (grp.art/record-error! env
         (merge {::grp.art/message (str "Failed to generate samples!")}
           extra))
       nil))))

(defn return-sample-generator-fn [env sampler]
  (fn [x]
    (try
      (return-sample-generator env sampler x)
      (catch #?(:clj Throwable :cljs :default) t
        (grp.art/record-error! env
          (merge {::grp.art/message (str "Failed to generate samples!")}
            {}))
        :FAIL))))

(>defn sample! [env fd argtypes]
  [::grp.art/env (s/keys :req [::grp.art/name ::grp.art/arities ::grp.art/fn-ref]) (s/coll-of ::grp.art/type-description) => ::grp.art/samples]
  (let [{::grp.art/keys [name arities fn-ref]} fd
        {::grp.art/keys [gspec]} (grp.art/get-arity arities argtypes)
        {::grp.art/keys [sampler return-spec generator]} gspec]
    (log/debug "sample!/sampler" name sampler)
    (try-sampling! env
      (gen/fmap (partial return-sample-generator env sampler)
        (gen/hash-map
          :args (apply gen/tuple (map gen/elements (map ::grp.art/samples argtypes)))
          :fn-ref (gen/return fn-ref)
          :params (gen/return (if (vector? sampler) (second sampler) sampler))
          :return-sample (or generator (s/gen return-spec))))
      {::grp.art/original-expression name})))

(>defn hashmap-permutation-generator [sample-map]
  [(s/map-of any? ::grp.art/samples) => ::generator]
  (->> sample-map
    (enc/map-vals gen/elements)
    (apply concat)
    (apply gen/hash-map)))
