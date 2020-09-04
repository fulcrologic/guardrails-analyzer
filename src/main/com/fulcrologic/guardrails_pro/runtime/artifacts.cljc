(ns com.fulcrologic.guardrails-pro.runtime.artifacts
  "The runtime storage of artifacts to analyze. This namespace is what caches the forms in the runtime environment
  and acts as the central control for finding, caching, renewing and expiring things from the runtime. These routines
  must work in CLJ and CLJS, and should typically not be hot reloaded during updates."
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [taoensso.timbre :as log])
  #?(:clj (:import (java.util Date))))

(def posint?
  (s/with-gen pos-int?
    #(gen/such-that pos? (gen/int))))

(def fn-gen #(gen/elements [string? int? keyword? symbol?]))

(s/def ::spec (s/with-gen
                (s/or
                  :spec-name qualified-keyword?
                  :spec-object #(s/spec? %)
                  :predicate ifn?)
                fn-gen))
(s/def ::type string?)
;; samples is for generated data only
(s/def ::samples (s/coll-of any? :min-count 1))
(s/def ::failing-samples ::samples)
(s/def ::original-expression any?)
(s/def ::literal-value ::original-expression)
(s/def ::message string?)
(s/def ::file string?)
(s/def ::source string?)
(s/def ::line-number posint?)
(s/def ::column-start posint?)
(s/def ::column-end posint?)
(s/def ::error (s/keys
                 :req [::original-expression ::message
                       ::file ::line-number]
                 :opt [::expected ::actual ::source
                       ::column-start ::column-end]))
(s/def ::warning ::error)
(s/def ::type-description (s/or
                            ;; NOTE: A gspec CAN be returned if an argument is a LAMBDA. HOF.
                            :function ::gspec
                            :value (s/keys :opt [::spec ::type ::samples ::literal-value ::original-expression])))
(s/def ::expected ::type-description)
(s/def ::actual (s/keys :opt [::type-description ::failing-samples]))
(s/def ::registry map?)
(s/def ::external-registry map?)
(s/def ::checking-sym qualified-symbol?)
(s/def ::current-form any?)
(s/def ::location (s/keys
                    :req [::line-number ::column-start ::column-end]
                    :opt [::source ::file]))
(s/def ::env (s/keys
               :req [::registry]
               :opt [::local-symbols ::extern-symbols
                     ::current-form ::checking-sym ::location]))
(s/def ::Unknown (s/and ::type-description empty?))
(s/def ::local-symbols (s/map-of symbol? ::type-description))
(s/def ::extern-symbols (s/map-of symbol? ::extern))
(s/def ::class? boolean?)
(s/def ::macro? boolean?)
(s/def ::extern (s/keys :req [::extern-name]
                  :opt [::class? ::macro? ::type-description ::value]))
(s/def ::name qualified-symbol?)
(s/def ::extern-name symbol?)
(s/def ::fn-ref (s/with-gen fn? fn-gen))
(s/def ::value any?)
(s/def ::arglist (s/or :vector vector? :quoted-vector (s/and seq? #(vector? (second %)))))
(s/def ::arg-types (s/coll-of ::type :kind vector?))
(s/def ::arg-predicates vector?)
(s/def ::arg-specs (s/coll-of ::spec :kind vector?))
(s/def ::return-type string?)
(s/def ::return-spec ::spec)
(s/def ::return-predicates (s/with-gen (s/coll-of fn? :kind vector?) fn-gen))
(s/def ::generator any?)
(s/def ::dispatch keyword?)
(s/def ::sampler (s/or
                   :kw ::dispatch
                   :vec (s/and vector? #(s/valid? ::dispatch (first %)))))
(s/def ::gspec (s/keys :req [::return-type ::return-spec]
                 :opt [::arg-types ::arg-specs
                       ::sampler ::generator
                       ::arg-predicates ::return-predicates]))
(s/def ::body any?)
(s/def ::raw-body vector?)
(s/def ::arity-detail (s/keys :req [::arglist ::gspec ::body]))
(s/def ::arity #{0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 :n})
(s/def ::arities (s/map-of ::arity ::arity-detail))
(s/def ::hashed int?)
(s/def ::last-changed posint?)
(s/def ::last-checked posint?)
(s/def ::last-seen posint?)
(s/def ::function (s/keys
                    :req [::name ::fn-ref
                          ::arities ::extern-symbols
                          ::last-changed ::last-seen ::location]
                    :opt [::last-checked ::hashed]))
(s/def ::errors (s/coll-of ::error))
(s/def ::warnings (s/coll-of ::warning))

(>defn get-arity
  [arities argtypes]
  [::arities (s/coll-of ::type-description) => ::arity-detail]
  (get arities (count argtypes) (get arities :n)))

(defonce external-registry (atom {}))

(defonce registry (atom {}))

(defn now [] (inst-ms (new #?(:cljs js/Date :clj Date))))

(>defn register-function!
  [fn-sym fn-desc]
  [qualified-symbol?
   (s/keys :req [::name ::fn-ref
                 ::arities ::location
                 ::last-changed ::extern-symbols])
   => any?]
  (let [{::keys [hashed arities extern-symbols] :as entry} (get @registry fn-sym)
        new-hash (hash [arities extern-symbols])]
    (swap! registry update fn-sym merge
      {::last-seen (now)}
      (if (= new-hash hashed) {}
        (assoc fn-desc ::hashed new-hash)))))

(defmulti cljc-rewrite-sym-ns-mm identity)
(defmethod cljc-rewrite-sym-ns-mm "clojure.core" [ns] #?(:cljs "cljs.core" :clj ns))
(defmethod cljc-rewrite-sym-ns-mm :default [ns] ns)

(>defn cljc-rewrite-sym-ns [sym]
  [symbol? => symbol?]
  (symbol (cljc-rewrite-sym-ns-mm (namespace sym))
    (name sym)))

(>defn register-external-function!
  [fn-sym fn-desc]
  [qualified-symbol? (s/keys :req [::name ::fn-ref ::arities ::last-changed]) => any?]
  (swap! external-registry assoc (cljc-rewrite-sym-ns fn-sym) fn-desc))

(>defn qualify-extern
  "Attempt to find a qualified symbol that goes with the given simple symbol.
   Returns unaltered sym if it isn't an extern."
  [env sym]
  [::env symbol? => symbol?]
  (get-in env [::extern-symbols sym ::extern-name] sym))

(>defn function-detail [env sym]
  [::env symbol? => (? ::function)]
  (let [sym (if (qualified-symbol? sym)
              sym (qualify-extern env sym))]
    (get-in env [::registry sym])))

(>defn external-function-detail [env sym]
  [::env symbol? => (? (s/keys :req [::name ::fn-ref ::arities ::last-changed]))]
  (let [sym (if (qualified-symbol? sym)
              sym (qualify-extern env sym))]
    (get-in env [::external-registry sym])))

(>defn symbol-detail [env sym]
  [::env symbol? => (? ::type-description)]
  (or
    (get-in env [::local-symbols sym])
    (get-in env [::extern-symbols sym ::type-description])))

(>defn remember-local [env sym td]
  [::env symbol? ::type-description => ::env]
  (assoc-in env [::local-symbols sym] td))

(defn clear-registry! [] (reset! registry {}))

(defn set-last-checked! [env sym ts]
  (swap! registry assoc-in [sym ::last-checked] ts))

(defn build-env
  ([] (build-env @registry))
  ([registry] (build-env registry @external-registry))
  ([registry external-registry]
   {::registry          registry
    ::external-registry external-registry}))

(>defn new-location
  [location]
  [map? => ::location]
  (let [location-remap {:source     ::source
                        :file       ::file
                        :line       ::line-number
                        :column     ::column-start
                        :end-column ::column-end}]
    (-> location
      (set/rename-keys location-remap)
      (select-keys (vals location-remap)))))

(>defn update-location
  [env location]
  [::env (? map?) => ::env]
  (log/info :update-location (::checking-sym env) location)
  (cond-> env location
    (assoc ::location
           (new-location location))))

(defonce problems (atom {}))

(>defn record-error!
  [env error]
  [::env (s/keys :req [::message ::original-expression]) => any?]
  (log/info :record-error! error (::checking-sym env) (::location env))
  (swap! problems update-in
    [(::checking-sym env) ::errors]
    (fnil conj [])
    (merge error
      (::location env))))

(>defn record-warning!
  [env warning]
  [::env (s/keys :req [::message ::original-expression]) => any?]
  (swap! problems update-in
    [(::checking-sym env) ::warnings]
    (fnil conj [])
    (merge warning
      (::location env))))

(defn clear-problems!
  ([sym]
   (swap! problems update sym assoc ::errors [] ::warnings []))
  ([]
   (swap! problems
     (partial reduce-kv
       (fn [m k v] (assoc m k (dissoc v ::errors ::warnings)))
       {}))))
