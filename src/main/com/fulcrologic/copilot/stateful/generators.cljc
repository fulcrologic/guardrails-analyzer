Copyright (c) Fulcrologic, LLC. All rights reserved.

Permission to use this software requires that you
agree to our End-user License Agreement, legally obtain a license,
and use this software within the constraints of the terms specified
by said license.

You may NOT publish, redistribute, or reproduce this software or its source
code in any form (printed, electronic, or otherwise) except as explicitly
allowed by your license agreement..

(ns com.fulcrologic.copilot.stateful.generators
  (:require
    [com.fulcrologic.copilot.stateful.rose-tree :as rose]
    [clojure.test.check.generators :as gen]))

(def ^:private ^:dynamic *state* ::none)

(defn- assert-stateful! []
  (when (= *state* ::none)
    (throw
      #?(:cljs (ex-info "A stateful generator was called within a non-stateful generator." {})
         :clj (IllegalStateException. "A stateful generator was called within a non-stateful generator.")))))

(defn stateful
  ([g] (stateful g {}))
  ([g initial-state]
   {:pre [(map? initial-state)]}
   (assoc g :gen
     (fn [rnd size]
       (cond
         (= *state* ::none)
         (let [scope initial-state
               tree (binding [*state* scope]
                      ((:gen g) rnd size))]
           (binding [*state* scope]
             (rose/bound-tree tree)))

         (empty? initial-state)
         ((:gen g) rnd size)

         :else
         (let [scope (merge *state* initial-state)]
           (set! *state* scope)
           ((:gen g) rnd size)))))))

(defn with-default-state [g default-state]
  (stateful
    (assoc g :gen
      (fn [rnd size]
        (let [scope (merge default-state *state*)]
          (set! *state* scope)
          ((:gen g) rnd size))))))

(defn get-state []
  (gen/->Generator
    (fn current-state-lookup [_ _]
      (assert-stateful!)
      (rose/fn-tree (fn [] *state*)))))

(defn get-value [ks & [default]]
  {:pre [(sequential? ks)]}
  (gen/->Generator
    (fn value-lookup [_ _]
      (assert-stateful!)
      (rose/fn-tree (fn [] (get-in *state* ks default))))))

(defn fmap [f gen]
  (stateful
    (gen/fmap
      (fn [[v st]]
        (f v st))
      (gen/tuple gen (get-state)))))

(defn bind [gen f]
  (stateful
    (gen/bind
      (gen/tuple gen (get-state))
      (fn [[v st]]
        (f v st)))))

(defn update-state [v f & args]
  {:pre [(not (gen/generator? v))]}
  (gen/->Generator
    (fn [_ _]
      (rose/fn-tree
        (fn []
          (assert-stateful!)
          (set! *state* (apply f *state* args))
          v)))))

(defn unique [collection-key gen]
  (gen/let [free? (gen/fmap (comp complement set) (get-value [collection-key]))
            value (gen/such-that free? gen 100)]
    (update-state value update collection-key (fnil conj #{}) value)))
