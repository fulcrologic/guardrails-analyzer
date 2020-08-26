(ns com.fulcrologic.guardrails-pro.runtime.artifacts
  "The runtime storage of artifacts to analyze. This namespace is what caches the forms in the runtime environment
  and acts as the central control for finding, caching, renewing and expiring things from the runtime. These routines
  must work in CLJ and CLJS, and should typically not be hot reloaded during updates."
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [taoensso.timbre :as log]))

(s/def ::spec (s/or
                :spec-name qualified-keyword?
                :spec-object #(s/spec? %)
                :predicate ifn?))
(s/def ::type string?)
;; samples is for generated data only
(s/def ::samples (s/coll-of any? :min-count 1))
(s/def ::failing-samples ::samples)
(s/def ::original-expression any?)
(s/def ::literal-value ::original-expression)
(s/def ::message string?)
(s/def ::column-start pos-int?)
(s/def ::column-end pos-int?)
(s/def ::error (s/keys
                 :req [::original-expression ::message ::line-number]
                 :opt [::expected ::actual
                       ::column-start ::column-end]))
(s/def ::warning ::error)
(s/def ::type-description (s/or
                            ;; NOTE: A gspec CAN be returned if an argument is a LAMBDA. HOF.
                            :function ::gspec
                            :value (s/keys :opt [::spec ::type ::samples ::literal-value ::original-expression])))
(s/def ::expected ::type-description)
(s/def ::actual (s/keys :opt [::type-description ::failing-samples]))
(s/def ::registry map?)
(s/def ::checking-sym qualified-symbol?)
(s/def ::current-form any?)
(s/def ::location map?)
(s/def ::env (s/keys
               :req [::registry]
               :opt [::local-symbols ::extern-symbols
                     ::current-form ::checking-sym ::location]))
(s/def ::Unknown (s/and ::type-description empty?))
(s/def ::local-symbols (s/map-of symbol? ::type-description))
(s/def ::extern-symbols (s/map-of symbol? ::extern))
(s/def ::class? boolean?)
(s/def ::macro? boolean?)
(s/def ::pure? boolean?)
(s/def ::extern (s/keys :req [::extern-name]
                  :opt [::class? ::macro? ::type-description ::value]))
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
(s/def ::errors (s/coll-of ::error))
(s/def ::warnings (s/coll-of ::warnings))

(>defn get-arity
  [arities argtypes]
  [::arities (s/coll-of ::type-description) => ::arity-detail]
  (get arities (count argtypes) (get arities :n)))

(defonce registry (atom {}))

(>defn remember!
  "Remember the given `form` under key `s` (typically the function's FQ sym)."
  [s function-description]
  [qualified-symbol? ::function => nil?]
  (swap! registry assoc s function-description)
  nil)

(>defn symbol-detail [env sym]
  [::env symbol? => (? ::type-description)]
  (or
    (get-in env [::local-symbols sym])
    (get-in env [::extern-symbols sym ::type-description])))

(>defn qualify-extern
  "Attempt to find a qualified symbol that goes with the given simple symbol. Returns unaltered sym if it isn't an extern."
  [env sym]
  [::env symbol? => symbol?]
  (get-in env [::extern-symbols sym ::extern-name] sym))

(>defn remember-local [env sym td]
  [::env symbol? ::type-description => ::env]
  (assoc-in env [::local-symbols sym] td))

(>defn function-detail [env sym]
  [::env symbol? => (? ::function)]
  (let [sym (if (qualified-symbol? sym)
              sym
              (qualify-extern env sym))]
    (get-in env [::registry sym])))

(>defn changed-since
  "Get a set of all symbols that have changed since tm (inst-ms)."
  [tm]
  [int? => (s/coll-of qualified-symbol? :kind set?)]
  (into #{}
    (keep (fn [{::keys [name last-changed]}]
            (when (> last-changed tm)
              name)))
    (vals @registry)))

(defn clear-registry! [] (reset! registry {}))

(defn build-env
  ([] {::registry @registry})
  ([registry] {::registry registry}))

(>defn update-location
  [env location]
  [::env (? map?) => ::env]
  (log/info :update-location (::checking-sym env) location)
  (cond-> env location
    (assoc ::location
      (select-keys location [:source :file :line :column]))))

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

(defn clear-problems! []
  (swap! problems
    (partial reduce-kv
      (fn [m k v] (assoc m k (dissoc v ::errors ::warnings)))
      {})))

(comment
  (-> (build-env)
    (function-detail 'com.fulcrologic.guardrails-pro.core/env-test)
    ::arities (get 1)
    ::body first meta)

  (deref problems)
  )
