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

(ns ^:clj-reload/no-reload
 com.fulcrologic.guardrails-analyzer.analysis.spec
  (:refer-clojure :exclude [-lookup])
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [clojure.test.check.generators :as tc.gen]
   [clojure.test.check.random :as tc.random]
   [clojure.test.check.rose-tree :as tc.rose]
   [com.fulcrologic.guardrails-analyzer.analytics :as cp.analytics]
   [com.fulcrologic.guardrails.malli.registry :as gr.malli.reg]
   [com.fulcrologic.guardrails-analyzer.log :as log]
   [malli.core :as m]
   [malli.generator :as mg]))

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

(defrecord MalliSpec [opts]
  ISpec
  (-lookup [this value]
    (try
      (m/schema value {:registry gr.malli.reg/registry})
      value
      (catch #?(:clj Exception :cljs :default) _ nil)))
  (-valid? [this spec value]
    (boolean (m/validate spec value {:registry gr.malli.reg/registry})))
  (-explain [this spec value]
    (let [explanation (m/explain spec value {:registry gr.malli.reg/registry})]
      (if explanation
        (pr-str explanation)
        "Success")))
  (-generator [this spec]
    (assoc (mg/generator spec {:registry gr.malli.reg/registry}) ::spec spec))
  (-generate [this spec]
    (mg/generate spec {:registry gr.malli.reg/registry}))
  (-sample [this spec]
    (seq (take (:num-samples opts) (sample-seq spec)))))

(defn with-spec-impl
  ([env impl-type] (with-spec-impl env impl-type {}))
  ([env impl-type opts]
   (let [opts (merge {:num-samples    10
                      :cache-samples? true}
                     opts)]
     (assoc env ::impl
            (case impl-type
              :clojure.spec.alpha (->ClojureSpecAlpha opts)
              :malli (->MalliSpec opts)
              (->ClojureSpecAlpha opts))))))

(defn with-both-impls
  "Initializes the env with both spec implementations available.
   The default active impl is ClojureSpecAlpha. Use `with-spec-system`
   to switch the active impl per-function."
  ([env] (with-both-impls env {}))
  ([env opts]
   (let [opts (merge {:num-samples 10 :cache-samples? true} opts)]
     (assoc env
            ::impl (->ClojureSpecAlpha opts)
            ::malli-impl (->MalliSpec opts)
            ::spec-impl (->ClojureSpecAlpha opts)))))

(defn with-spec-system
  "Switch the active spec impl in env based on the spec-system keyword.
   :org.clojure/spec1 or nil -> ClojureSpecAlpha (default)
   :malli -> MalliSpec"
  [env spec-system]
  (case spec-system
    :malli (if-let [malli-impl (::malli-impl env)]
             (assoc env ::impl malli-impl)
             env)
    (if-let [spec-impl (::spec-impl env)]
      (assoc env ::impl spec-impl)
      env)))

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
