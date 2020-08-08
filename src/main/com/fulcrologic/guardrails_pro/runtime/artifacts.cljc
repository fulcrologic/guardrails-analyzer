(ns com.fulcrologic.guardrails-pro.runtime.artifacts
  "The runtime storage of artifacts to analyze. This namespace is what caches the forms in the runtime environment
  and acts as the central control for finding, caching, renewing and expiring things from the runtime. These routines
  must work in CLJ and CLJS, and should typically not be hot reloaded during updates."
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn => | ?]]
    [clojure.spec.alpha :as s]))

(s/def ::spec (s/or
                :spec-name qualified-keyword?
                :spec-object #(s/spec? %)
                :predicate fn?))
(s/def ::type string?)
(s/def ::samples (s/coll-of any? :min-count 1))
(s/def ::original-expression any?)
(s/def ::message string?)
(s/def ::error (s/keys :req [::original-expression ::expected ::found ::message]))
(s/def ::errors (s/coll-of ::error))
(s/def ::type-description (s/or
                            :function ::gspec
                            :value (s/keys :opt [::spec ::type ::samples ::original-expression ::errors])))
(s/def ::expected ::type-description)
(s/def ::found ::type-description)
(s/def ::registry map?)
(s/def ::env (s/keys
               :req [::registry]
               :opt [::local-symbols ::extern-symbols]))
(s/def ::Unknown (s/and ::type-description empty?))
(s/def ::local-symbols (s/map-of symbol? ::type-description))
(s/def ::extern-symbols (s/map-of symbol? ::extern))
(s/def ::class? boolean?)
(s/def ::macro? boolean?)
(s/def ::pure? boolean?)
(s/def ::extern (s/keys :req [::extern-name ::value]
                  :opt [::class? ::macro? ::type-description]))
(s/def ::name qualified-symbol?)
(s/def ::extern-name symbol?)
(s/def ::fn-ref fn?)
(s/def ::value any?)
(s/def ::arglist vector?)
(s/def ::arg-types (s/coll-of ::type :kind vector?))
(s/def ::arg-predicates vector?)
(s/def ::arg-specs (s/coll-of ::spec :kind vector?))
(s/def ::return-type string?)
(s/def ::return-spec ::spec)
(s/def ::return-predicates (s/coll-of fn? :kind vector?))
(s/def ::generator any?)
(s/def ::pure? boolean?)
(s/def ::dispatch keyword?)
(s/def ::typecalc (s/or
                    :kw ::dispatch
                    :map (s/keys :req [::dispatch])
                    :vec (s/and vector? #(s/valid? ::dispatch (first %)))))

(s/def ::gspec (s/keys :req [::arg-types ::arg-specs ::return-type ::return-spec]
                 :opt [::generator ::arg-predicates ::return-predicates
                       ::pure? ::typecalc]))
(s/def ::body any?)
(s/def ::raw-body vector?)
(s/def ::arity-detail (s/keys :req [::arglist ::gspec ::body]))
(s/def ::arity #{1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 :n})
(s/def ::arities (s/map-of ::arity ::arity-detail))
(s/def ::function (s/keys :req [::name ::last-changed ::fn-ref ::arities ::extern-symbols]))
(s/def ::last-changed pos-int?)

(defonce memory (atom {}))

(>defn remember!
  "Remember the given `form` under key `s` (typically the function's FQ sym)."
  [s function-description]
  [qualified-symbol? ::function => nil?]
  (swap! memory assoc s function-description)
  nil)

(>defn function-detail
  ([env sym]
   [::env qualified-symbol? => (? ::function)]
   (get-in env [::registry sym]))
  ([sym]
   [qualified-symbol? => (? ::function)]
   (get @memory sym)))

(>defn changed-since
  "Get a set of all symbols that have changed since tm (inst-ms)."
  [tm]
  [int? => (s/coll-of qualified-symbol? :kind set?)]
  (into #{}
    (keep (fn [{::keys [name last-changed]}]
            (when (> last-changed tm)
              name)))
    (vals @memory)))

(defn clear! [] (reset! memory {}))
(defn clear-problems! []
  (swap! memory (fn [m]
                  (reduce-kv
                    (fn [m k v] (assoc m k (dissoc v ::problems)))
                    {}
                    m))))

(defn record-problem! [sym metadata description]
  (swap! memory update-in [sym ::problems] (fnil conj []) {:metadata    metadata
                                                           :description description}))

(defn build-env
  ([] {::registry @memory})
  ([registry] {::registry registry}))

