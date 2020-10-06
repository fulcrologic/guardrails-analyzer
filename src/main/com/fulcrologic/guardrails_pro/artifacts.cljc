(ns com.fulcrologic.guardrails-pro.artifacts
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails.core :refer [>def >defn >defn- => | ?]]
    [com.fulcrologic.guardrails.registry :as gr.reg]
    [com.fulcrologic.guardrails.impl.externs :as gr.externs]
    [com.rpl.specter :as $]
    [taoensso.timbre :as log]))

;; ========== CLJC SYM REWRITE ==========

(defmulti cljc-rewrite-sym-ns-mm identity)
(defmethod cljc-rewrite-sym-ns-mm "cljs.core" [ns] "clojure.core")
(defmethod cljc-rewrite-sym-ns-mm :default [ns] ns)

(>defn cljc-rewrite-sym-ns [sym]
  [symbol? => symbol?]
  (symbol (cljc-rewrite-sym-ns-mm (namespace sym))
    (name sym)))

;; ========== ARTIFACTS ==========

(def posint?
  (s/with-gen pos-int?
    #(gen/such-that pos? gen/int)))

(def gen-predicate #(gen/elements [string? int? keyword? symbol?]))

(>def ::spec (s/with-gen
                (s/or
                  :spec-name qualified-keyword?
                  :spec-object #(s/spec? %)
                  :predicate ifn?)
                gen-predicate))
(>def ::type string?)
(>def ::form any?) ;; TODO
(>def ::samples
  "samples is for generated data only"
  (s/coll-of any? :min-count 0 :kind set?))
(>def ::failing-samples ::samples)
(>def ::original-expression ::form)
(>def ::literal-value ::original-expression)
(>def ::problem-type (s/and qualified-keyword?
                        (comp #{"error" "warning" "info" "hint"} namespace)))
(>def ::message-params (s/map-of keyword? some?))
(>def ::file string?)
(>def ::source string?)
(>def ::line-start posint?)
(>def ::line-end posint?)
(>def ::column-start posint?)
(>def ::column-end posint?)
(>def ::key (s/or
               :offset int?
               :typed-key qualified-keyword?
               ;:homogenous ::homogenous
               :arbitrary any?))
(>def ::positional-types (s/map-of ::key ::type-description))
(>def ::recursive-description (s/keys :req [::positional-types]))
;; NOTE: We can use a generated sample to in turn generate a recursive description
(>def ::type-description (s/or
                            :function ::lambda
                            :value (s/keys :opt [::spec
                                                 ::recursive-description
                                                 ::type
                                                 ::samples
                                                 ::literal-value
                                                 ::original-expression])))
(>def ::expected (s/keys :req [(or ::spec ::type)]))
(>def ::actual (s/keys :opt [::type-description ::failing-samples]))
(>def ::Unknown (s/and ::type-description empty?))
(>def ::name qualified-symbol?)
(>def ::fn-ref (s/with-gen fn? #(gen/let [any gen/any] (constantly any))))
(>def ::arglist (s/or :vector (s/coll-of simple-symbol? :kind vector?)
                  :quoted-vector (s/cat :quote #{'quote}
                                   :symbols (s/coll-of simple-symbol? :kind vector?))))
(>def ::predicate (s/with-gen fn? gen-predicate))
(>def ::argument-predicates (s/coll-of ::predicate :kind vector?))
(>def ::argument-types (s/coll-of ::type :kind vector?))
(>def ::return-type ::type)
(>def ::argument-specs (s/coll-of ::spec :kind vector?))
(>def ::return-spec ::spec)
(>def ::quoted.argument-specs (s/coll-of ::form :kind vector?))
(>def ::quoted.return-spec ::form)
(>def ::return-predicates (s/coll-of ::predicate :kind vector?))
(>def ::generator (s/with-gen gen/generator? #(gen/return gen/any)))
(>def ::metadata map?)
(>def ::gspec (s/keys :req [::return-spec ::return-type]
                 :opt [::argument-specs ::metadata ::generator
                       ::argument-predicates ::return-predicates]))
(>def ::arity-detail (s/keys :req [::arglist ::gspec]))
(>def ::arity #{0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 :n})
(>def ::arities (s/map-of ::arity ::arity-detail))
(>def ::last-changed posint?)
(>def ::last-checked posint?)
(>def ::last-seen posint?)
(>def ::env->fn (s/with-gen fn? #(gen/let [any gen/any] (fn [env] (constantly any)))))
(>def ::lambda-name simple-symbol?)
(>def ::lambda (s/keys :req [::env->fn ::arities] :opt [::lambda-name]))
(>def ::lambdas (s/map-of symbol? ::lambda))
(>def ::fn-name symbol?)
(>def ::var-name qualified-symbol?)
(>def ::function (s/keys :req [(or ::fn-name ::var-name) ::fn-ref ::arities]))
(>def ::value any?)
(>def ::class? boolean?)
(>def ::extern-name symbol?)
(>def ::extern (s/keys :req [::extern-name] :opt [::class? ::value]))
(>def ::location (s/keys
                    :req [::line-start ::column-start]
                    :opt [::line-end ::column-end]))
(>def ::checking-file string?)
(>def ::checking-sym symbol?)
(>def ::current-form ::form)
(>def ::current-ns string?)
(>def ::local-symbols (s/map-of symbol? ::type-description))
(>def ::externs-registry (s/map-of string? (s/map-of symbol? (s/map-of symbol? ::extern))))
(>def ::spec-registry (s/map-of ::form ::spec))
(>def ::external-function-registry (s/map-of qualified-symbol? ::function))
(>def ::function-registry (s/map-of string? (s/map-of symbol? ::function)))
(>def ::env (s/keys
               :req [::function-registry]
               :opt [::external-function-registry
                     ::externs-registry
                     ::spec-registry
                     ::local-symbols
                     ::checking-sym
                     ::current-form
                     ::current-ns
                     ::location]))

(>defn get-arity
  [arities argtypes]
  [::arities (s/coll-of ::type-description) => ::arity-detail]
  (get arities (count argtypes) (get arities :n)))

(defn- fix-kw-nss [x]
  ($/transform [($/walker qualified-keyword?)
                $/NAMESPACE ($/pred= (namespace ::gr.reg/_))]
    (constantly (namespace ::this))
    x))

(>defn lookup-spec [env quoted-spec]
  [(s/keys :req [::spec-registry]) ::form => ::spec]
  (or (get-in env [::spec-registry quoted-spec])
    (throw (ex-info "WIP: lookup-spec failed to lookup"
             {:spec quoted-spec}))))

(defn resolve-quoted-specs [spec-registry registry]
  (let [env {::spec-registry spec-registry}
        RESOLVE (fn [m]
                  (cond-> m
                    (::quoted.argument-specs m)
                    #_=> (assoc ::argument-specs
                           (mapv (partial lookup-spec env)
                             (::quoted.argument-specs m)))
                    (::quoted.return-spec m)
                    #_=> (assoc ::return-spec
                           (lookup-spec env
                             (::quoted.return-spec m)))))]
    ($/transform ($/walker (some-fn ::quoted.argument-specs ::quoted.return-spec))
      RESOLVE registry)))

(>defn build-env
  ([] [=> ::env] (build-env @gr.externs/function-registry))
  ([function-registry] [map? => ::env]
   (build-env function-registry @gr.externs/external-function-registry))
  ([function-registry external-function-registry]
   [map? map? => ::env]
   (let [spec-registry @gr.externs/spec-registry]
     (-> {::external-function-registry (->> external-function-registry
                                         (fix-kw-nss)
                                         (resolve-quoted-specs spec-registry))
          ::externs-registry           (fix-kw-nss @gr.externs/externs-registry)
          ::function-registry          (->> function-registry
                                         (fix-kw-nss)
                                         (resolve-quoted-specs spec-registry))
          ::spec-registry              spec-registry}
       (grp.spec/with-spec-impl :clojure.spec.alpha)))))

(>defn qualify-extern
  [env sym]
  [::env simple-symbol? => symbol?]
  (log/debug "qualify-extern:" sym
    "checking-sym:" (pr-str (::checking-sym env))
    "current-ns:" (pr-str (::current-ns env))
    "externs:" (get-in env [::externs-registry (::checking-sym env)]))
  (log/spy :debug "qualify-extern/return"
    (cljc-rewrite-sym-ns
      (get-in env [::externs-registry (::current-ns env)
                   (::checking-sym env) sym ::extern-name]
        #_:or sym))))

(>defn function-detail [env sym]
  [::env simple-symbol? => (? ::function)]
  (log/debug "function-detail:" (pr-str sym)
    "current-ns:" (pr-str (::current-ns env))
    "ns-fns:" (keys (get-in env [::function-registry (::current-ns env)] {})))
  (log/spy :debug "function-detail/return"
    (get-in env [::function-registry (::current-ns env) sym])))

(>defn external-function-detail [env sym]
  [::env symbol? => (? (s/keys :req [::name ::fn-ref ::arities]))]
  (log/spy :debug "external-function-detail/return"
    (let [sym (if (qualified-symbol? sym)
                (cljc-rewrite-sym-ns sym)
                (qualify-extern env sym))]
      (log/debug "external-function-detail" sym
        "in" (::checking-sym env))
      (get-in env [::external-function-registry sym]))))

(>defn symbol-detail [env sym]
  [::env symbol? => (? ::type-description)]
  (log/debug "symbol-detail" sym)
  (log/spy :debug "symbol-detail/return"
    (or
      (get-in env [::local-symbols sym])
      (get-in env [::externs-registry (::current-ns env) sym ::type-description]))))

(>defn remember-local [env sym td]
  [::env symbol? ::type-description => ::env]
  (assoc-in env [::local-symbols sym] td))

(>defn lookup-symbol
  "Used by >fn to lookup sample values for symbols"
  [env sym]
  [::env symbol? => (? some?)]
  (let [{::keys [samples]} (get-in env [::local-symbols sym])]
    (and (seq samples) (rand-nth (vec samples)))))

;; ========== LOCATION ==========

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

;; ========== BINDINGS ==========

(defonce binding-annotations (atom {}))

(defn clear-bindings!
  "Clear all of the binding information for symbols in the given the symbolic name of a fully-qualified function."
  [] (reset! binding-annotations {}))

(>defn record-binding!
  "Report a type description for the given symbol, which must have file/line/column metadata in order to be recorded."
  [env sym type-description]
  [::env simple-symbol? ::type-description => any?]
  (let [env (update-location env (meta sym))
        {::keys [checking-file location]} env
        {::keys [line-start column-start]} location]
    (let [location-info (-> location
                         (select-keys [::line-start ::column-start])
                         (assoc ::file checking-file))]
      (if (and checking-file line-start column-start)
        (swap! binding-annotations assoc location-info
          (assoc type-description ::original-expression sym))
        (log/warn "Cannot record binding because we don't know enough location info"
          location-info)))
    nil))

;; ========== PROBLEMS ==========

(>def ::problem (s/keys
                  :req [::original-expression ::problem-type
                        ::file ::line-start]
                  :opt [::expected ::actual
                        ::column-start ::column-end
                        ::line-end ::message-params]))
(>def ::error (s/and ::problem (comp #{"error"} namespace ::problem-type)))
(>def ::warning (s/and ::problem (comp #{"warning"} namespace ::problem-type)))
(>def ::info (s/and ::problem (comp #{"info"} namespace ::problem-type)))
(>def ::hint (s/and ::problem (comp #{"hint"} namespace ::problem-type)))
(>def ::errors (s/coll-of ::error))
(>def ::warnings (s/coll-of ::warning))
(>def ::infos (s/coll-of ::infos))
(>def ::hints (s/coll-of ::hints))
(>def ::by-sym (s/map-of qualified-symbol?
                 (s/keys :opt [::errors ::warnings ::infos ::hints])))
(>def ::problems (s/keys :req [::by-sym]))

(defonce problems (atom {::by-sym {}}))

(>defn- insert-problem-by-sym [problems problem-list-type sym problem]
  [::problems #{::errors ::warnings} qualified-symbol? ::problem => ::problems]
  (update-in problems
    [::by-sym sym problem-list-type]
    (fnil conj [])
    problem))

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
   (swap! problems insert-problem-by-sym ::errors (::checking-sym env)
     (merge error (::location env) {::file (::checking-file env)}))
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
   (swap! problems insert-problem-by-sym ::warnings (::checking-sym env)
     (merge warning (::location env) {::file (::checking-file env)}))
   nil))

(>defn record-info!
  ([env original-expression problem-type]
   [::env ::original-expression ::problem-type => nil?]
   (record-info! env original-expression problem-type {}))
  ([env original-expression problem-type message-params]
   [::env ::original-expression ::problem-type ::message-params => nil?]
   (record-info! env {::problem-type problem-type
                      ::original-expression original-expression
                      ::message-params message-params}))
  ([env info]
   [::env (s/keys :req [::problem-type ::original-expression]) => nil?]
   (swap! problems update-in [::infos (::location env)]
     (fnil conj [])
     (merge info (::location env) {::file (::checking-file env)}))
   nil))

(defn- clear-problems-by-file [problems file]
  ($/setval [($/walker ::file) ($/pred (comp (partial = file) ::file))]
    $/NONE problems))

(defn clear-problems!
  ([] (swap! problems dissoc ::errors ::warnings ::infos ::hints))
  ([file] (swap! problems clear-problems-by-file file)))
