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
  (-sample [this spec])
  (-cache-key [this spec]))

(defrecord ClojureSpecAlpha [opts]
  ISpec
  (-lookup [this value] (s/get-spec value))
  (-valid? [this spec value] (s/valid? spec value))
  (-explain [this spec value] (s/explain-str spec value))
  (-generator [this spec] (assoc (s/gen spec) ::spec spec))
  (-generate [this spec]
    ;; Accept either a test.check Generator (passed by sampler) or a spec/predicate
    ;; (passed by direct callers). For specs/predicates, derive a generator via s/gen.
    (gen/generate
     (if (instance? clojure.test.check.generators.Generator spec)
       spec
       (s/gen spec))))
  (-sample [this spec] (seq (take (:num-samples opts) (sample-seq spec))))
  (-cache-key [this spec]
    ;; Normalize to s/form so semantically-equal specs share cache entries.
    ;; Important for inline forms like `(s/and pos? int?)` whose spec object
    ;; is freshly built each call (causing the prior identity-keyed cache to
    ;; degenerate to a no-op while still accumulating entries).
    (try
      (let [f (s/form spec)]
        (cond
          (= f :clojure.spec.alpha/unknown) spec
          (identical? f spec) spec
          :else f))
      (catch #?(:clj Throwable :cljs :default) _ spec))))

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
    (seq (take (:num-samples opts) (sample-seq spec))))
  (-cache-key [this spec]
    ;; Normalize via m/form for the same reason as ClojureSpecAlpha — inline
    ;; schema vectors `[:and pos? int?]` parse to fresh schema objects per call.
    (try (m/form spec {:registry gr.malli.reg/registry})
         (catch #?(:clj Throwable :cljs :default) _ spec))))

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

;; Audit instrumentation for the sample cache. Counts are reset per check
;; alongside the cache itself (see `with-empty-cache`). Exposed via
;; `cache-stats-snapshot` so the user can verify cache effectiveness after
;; running a workload (e.g. the test suite) without having to re-instrument.
(defonce cache-stats (atom {:hits 0 :misses 0}))

(defn cache-stats-snapshot
  "Returns the current sample-cache hit/miss counts and ratio. Useful for
   manual auditing of cache effectiveness — call after running a workload."
  []
  (let [{:keys [hits misses] :as s} @cache-stats
        total (+ hits misses)]
    (assoc s
           :total total
           :hit-rate (when (pos? total) (double (/ hits total))))))

(defn reset-cache-stats! [] (reset! cache-stats {:hits 0 :misses 0}))

(defn sample [env gen]
  (if-not (:cache-samples? (:opts (::impl env)))
    (-sample (::impl env) gen)
    (let [impl (::impl env)
          spec (::spec gen gen)
          ;; Normalized key (see `-cache-key`) — collapses semantically-equal
          ;; inline specs onto a single entry rather than keying on the freshly
          ;; allocated impl object that the prior implementation used.
          k    (-cache-key impl spec)]
      (if-let [samples (get @cache k)]
        (do (swap! cache-stats update :hits inc)
            (log/debug "Using cached samples for" k)
            samples)
        (cp.analytics/profile ::new-samples
                              (let [samples (-sample impl gen)]
                                (swap! cache-stats update :misses inc)
                                (log/debug "Caching new samples for:" k)
                                (swap! cache assoc k samples)
                                samples))))))

(defn with-empty-cache [f & args]
  (reset! cache {})
  (reset! cache-stats {:hits 0 :misses 0})
  (apply f args))
