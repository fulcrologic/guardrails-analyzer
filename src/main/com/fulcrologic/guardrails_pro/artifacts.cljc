(ns com.fulcrologic.guardrails-pro.artifacts
  "The runtime storage of artifacts to analyze. This namespace is what caches the forms in the runtime environment
  and acts as the central control for finding, caching, renewing and expiring things from the runtime. These routines
  must work in CLJ and CLJS, and should typically not be hot reloaded during updates."
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [taoensso.timbre :as log]
    [taoensso.encore :as enc])
  #?(:clj (:import (java.util Date))))

(defn now-ms []
  (inst-ms (new #?(:clj Date :cljs js/Date))))

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
(s/def ::samples (s/coll-of any? :min-count 0 :kind set?))
(s/def ::failing-samples ::samples)
(s/def ::original-expression any?)
(s/def ::literal-value ::original-expression)
(s/def ::problem-type (s/and qualified-keyword?
                        (comp #{"error" "warning" "info" "hint"} namespace)))
(s/def ::message-params (s/map-of keyword? some?))
(s/def ::file string?)
(s/def ::source string?)
(s/def ::line-start posint?)
(s/def ::line-end posint?)
(s/def ::column-start posint?)
(s/def ::column-end posint?)
(s/def ::error (s/keys
                 :req [::original-expression ::problem-type
                       ::file ::line-start]
                 :opt [::expected ::actual ::source
                       ::column-start ::column-end
                       ::line-end ::message-params]))
(s/def ::warning ::error)
(s/def ::key (s/or
               :offset int?
               :typed-key qualified-keyword?
               :homogenous ::homogenous
               :arbitrary any?))
(s/def ::positional-types (s/map-of ::key ::type-description))
(s/def ::recursive-description (s/keys :req [::positional-types]))
;; NOTE: We can use a generated sample to in turn generate a recursive description
(s/def ::type-description (s/or
                            :function ::lambda
                            :value (s/keys :opt [::spec
                                                 ::recursive-description
                                                 ::type
                                                 ::samples
                                                 ::literal-value
                                                 ::original-expression])))
(s/def ::expected (s/keys :req [(or ::spec ::type)]))
(s/def ::actual (s/keys :opt [::type-description ::failing-samples]))
(s/def ::registry map?)
(s/def ::external-registry map?)
(s/def ::checking-sym qualified-symbol?)
(s/def ::current-form any?)
(s/def ::location (s/keys
                    :req [::line-start ::column-start]
                    :opt [::line-end ::column-end]))
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
(s/def ::metadata map?)
(s/def ::gspec (s/keys :req [::return-type ::return-spec]
                 :opt [::arg-types ::arg-specs
                       ::metadata ::generator
                       ::arg-predicates ::return-predicates]))
(s/def ::body any?)
(s/def ::raw-body vector?)
(s/def ::arity-detail (s/keys :req [::arglist ::gspec] :opt [::body]))
(s/def ::arity #{0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 :n})
(s/def ::arities (s/map-of ::arity ::arity-detail))
(s/def ::last-changed posint?)
(s/def ::last-checked posint?)
(s/def ::last-seen posint?)
(s/def ::env->fn fn?)
(s/def ::lambda-name simple-symbol?)
(s/def ::lambda (s/keys :req [::lambda-name ::env->fn ::arities]))
(s/def ::lambdas (s/map-of symbol? ::lambda))
(s/def ::function (s/keys
                    :req [::name ::fn-ref ::lambdas
                          ::arities ::extern-symbols
                          ::last-changed ::last-seen ::location]
                    :opt [::last-checked]))
(s/def ::errors (s/coll-of ::error))
(s/def ::warnings (s/coll-of ::warning))

(>defn get-arity
  [arities argtypes]
  [::arities (s/coll-of ::type-description) => ::arity-detail]
  (get arities (count argtypes) (get arities :n)))

(defonce external-registry (atom {}))

(defonce registry (atom {}))

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
  (swap! external-registry assoc (cljc-rewrite-sym-ns fn-sym) fn-desc)
  nil)

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
    (log/spy :info (str "symbol-detail for local: " sym)
      (get-in env [::local-symbols sym]))
    (log/spy :info (str "symbol-detail for extern: " sym)
      (get-in env [::extern-symbols sym ::type-description]))))

(>defn lookup-symbol [env sym]
  [::env symbol? => (? any?)]
  (let [{::keys [samples]} (get-in env [::local-symbols sym])]
    (and (seq samples) (rand-nth (vec samples)))))

(>defn remember-local [env sym td]
  [::env symbol? ::type-description => ::env]
  (log/info "Remembering samples for " sym " as " (::samples td))
  (assoc-in env [::local-symbols sym] td))

(defn clear-registry! [] (reset! registry {}))

(defn set-last-checked! [env sym ts]
  (swap! registry assoc-in [sym ::last-checked] ts))

(defn build-env
  ([] (build-env @registry))
  ([registry] (build-env registry @external-registry))
  ([registry external-registry]
   (-> {::registry          registry
        ::external-registry external-registry}
     (grp.spec/with-spec-impl :clojure.spec.alpha))))

(>defn new-location
  [location]
  [map? => ::location]
  (let [location-remap {:line       ::line-start
                        :end-line   ::line-end
                        :column     ::column-start
                        :end-column ::column-end}]
    (-> location
      (set/rename-keys location-remap)
      (select-keys (vals location-remap)))))

(>defn update-location
  [env location]
  [::env (? map?) => ::env]
  (log/trace :update-location (::checking-sym env) location)
  (cond-> env location
    (assoc ::location
      (new-location location))))

(defonce binding-annotations (atom {}))

(defn clear-bindings!
  "Clear all of the binding information for symbols in the given the symbolic name of a fully-qualified function."
  []
  (reset! binding-annotations {}))

(>defn record-binding!
  "Report a type description for the given symbol, which must have file/line/column metadata in order to be recorded."
  [env sym type-description]
  [::env simple-symbol? ::type-description => any?]
  (let [env (update-location env (meta sym))
        {::keys [checking-sym location]} env
        {::keys [line-start column-start file]} location]
    (if (and line-start column-start file)
      (swap! binding-annotations assoc location (assoc type-description
                                                  ::original-expression sym))
      (log/warn "Cannot record binding because we don't know enough location info" file line-start column-start))
    nil))

(defonce problems (atom {}))

(defn insert-into-indexes [problems problem-list-type problem]
  (-> problems
    (update-in
      [problem-list-type ::indexed (::file problem) (::line-start problem) (::column-start problem)]
      (fnil conj [])
      problem)
    (update-in ;; index from sym to all indexed paths
      [problem-list-type ::sym->index-paths (::checking-sym problem)]
      (fnil conj [])
      [(::file problem) (::line-start problem) (::column-start problem)])
    (update-in
      [problem-list-type ::by-sym (::checking-sym problem)]
      (fnil conj [])
      problem)))

(>defn record-error!
  ([env original-expression problem-type]
   [::env ::original-expression ::problem-type => nil?]
   (record-error! env original-expression problem-type {}))
  ([env original-expression problem-type message-params]
   [::env ::original-expression ::problem-type ::message-params => nil?]
   (record-error! env {::problem-type problem-type
                       ::original-expression original-expression
                       ::message-params message-params}))
  ([env error]
   [::env (s/keys :req [::problem-type ::original-expression]) => nil?]
   (log/info :record-error! (::checking-sym env) "\n" (::location env) "\n" error)
   (swap! problems
     #(insert-into-indexes % ::errors
        (merge error
          (::location env)
          {::checking-sym (::checking-sym env)})))
   nil))

(>defn record-warning!
  ([env original-expression problem-type]
   [::env ::original-expression ::problem-type => nil?]
   (record-warning! env original-expression problem-type {}))
  ([env original-expression problem-type message-params]
   [::env ::original-expression ::problem-type ::message-params => nil?]
   (record-warning! env {::problem-type problem-type
                         ::original-expression original-expression
                         ::message-params message-params}))
  ([env warning]
   [::env (s/keys :req [::problem-type ::original-expression]) => nil?]
   (swap! problems
     #(insert-into-indexes % ::warnings
        (merge warning
          (::location env)
          {::checking-sym (::checking-sym env)})))
   nil))

(defn clear-problems [P sym]
  (let [index-paths (concat
                      (get-in P [::warnings ::sym->index-paths sym])
                      (get-in P [::errors   ::sym->index-paths sym]))]
    (-> (reduce (fn [m path]
                  (-> m
                    (update-in (concat [::errors   ::indexed] path)
                      (partial remove (comp #{sym} ::checking-sym)))
                    (update-in (concat [::warnings ::indexed] path)
                      (partial remove (comp #{sym} ::checking-sym)))))
          P index-paths)
      (enc/dissoc-in [::warnings ::sym->index-paths] sym)
      (enc/dissoc-in [::errors   ::sym->index-paths] sym)
      (assoc-in [::warnings ::by-sym sym] [])
      (assoc-in [::errors   ::by-sym sym] []))))

(defn clear-problems!
  ([] (swap! problems dissoc ::warnings ::errors))
  ([sym] (swap! problems clear-problems sym)))
