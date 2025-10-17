;; Copyright (c) Fulcrologic, LLC. All rights reserved.
;;
;; Permission to use this software requires that you
;; agree to our End-user License Agreement, legally obtain a license,
;; and use this software within the constraints of the terms specified
;; by said license.
;;
;; You may NOT publish, redistribute, or reproduce this software or its source
;; code in any form (printed, electronic, or otherwise) except as explicitly
;; allowed by your license agreement..

(ns ^{:clojure.tools.namespace.repl/load false}
  com.fulcrologic.guardrails-analyzer.analysis.spec
  (:refer-clojure :exclude [-lookup])
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [clojure.test.check.generators :as tc.gen]
    [clojure.test.check.random :as tc.random]
    [clojure.test.check.rose-tree :as tc.rose]
    [com.fulcrologic.guardrails-analyzer.analytics :as cp.analytics]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defn make-size-range-seq [max-size]
  (cycle (mapcat #(repeat 5 %) (range 0 max-size))))

(defn sample-seq
  ([gen] (sample-seq gen 10))
  ([gen max-size]
   (let [r        (tc.random/make-random)
         size-seq (make-size-range-seq max-size)]
     (map #(tc.rose/root (tc.gen/call-gen gen %1 %2))
       (tc.gen/lazy-random-states r)
       size-seq))))

(defprotocol ISpec
  (-lookup [this value])
  (-valid? [this spec value])
  (-explain [this spec value])
  (-generator [this spec])
  (-generate [this spec])
  (-sample [this spec]))

(defrecord ClojureSpecAlpha [opts]
  ISpec
  (-lookup [this value] (s/get-spec value))
  (-valid? [this spec value] (s/valid? spec value))
  (-explain [this spec value] (s/explain-str spec value))
  (-generator [this spec] (assoc (s/gen spec) ::spec spec))
  (-generate [this spec] (gen/generate spec))
  (-sample [this spec] (seq (take (:num-samples opts) (sample-seq spec)))))

(comment
  (defrecord Malli [opts]
    ISpec
    (-lookup [this value] (malli/schema value))
    (-valid? [this spec value] (boolean (malli/validate spec value)))
    (-explain [this spec value] (malli/explain spec value))
    (-generator [this spec] (malli/generator spec))
    (-generate [this spec] (malli/generate spec))
    (-sample [this spec] (malli/sample spec)))
  )

(defn with-spec-impl
  ([env impl-type] (with-spec-impl env impl-type {}))
  ([env impl-type opts]
   (let [opts (merge {:num-samples    10
                      :cache-samples? true}
                opts)]
     (assoc env ::impl
                (case impl-type
                  :clojure.spec.alpha (->ClojureSpecAlpha opts)
                  (->ClojureSpecAlpha opts))))))

(defn lookup [env value] (-lookup (::impl env) value))
(defn valid? [env spec value] (cp.analytics/profile ::valid? (-valid? (::impl env) spec value)))
(defn explain [env spec value] (-explain (::impl env) spec value))
(defn generator [env spec]
  (try (-generator (::impl env) spec)
       (catch #?(:clj Exception :cljs :default) e
         nil)))
(defn generate [env spec] (-generate (::impl env) spec))

(defonce cache (atom {}))

(defn sample [env gen]
  (if-not (:cache-samples? (:opts (::impl env)))
    (-sample (::impl env) gen)
    (let [spec (::spec gen gen)]
      (if-let [samples (get @cache spec)]
        (do (log/debug "Using cached samples for" spec)
            samples)
        (cp.analytics/profile ::new-samples
          (let [samples (-sample (::impl env) gen)]
            (log/debug "Caching new samples for:" spec)
            (swap! cache assoc spec samples)
            samples))))))

(defn with-empty-cache [f & args]
  (reset! cache {})
  (apply f args))
