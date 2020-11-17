(ns com.fulcrologic.guardrails-pro.artifacts
  #?(:cljs (:require-macros clojure.test.check.generators))
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.guardrails.registry :as gr.reg]
    [com.fulcrologic.guardrails.impl.externs :as gr.externs]
    [com.fulcrologic-pro.com.rpl.specter :as $]
    [com.fulcrologic.guardrails-pro.logging :as log]))

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

(def gen-predicate #(gen/return (fn [& _] (rand-nth [true false]))))
#_(map (fn [pf] (pf)) (gen/sample (gen-predicate)))

(s/def ::spec (s/with-gen
                (s/or
                  :spec-name qualified-keyword?
                  :spec-object #(s/spec? %)
                  :predicate ifn?)
                gen-predicate))
(s/def ::type string?)
(s/def ::form any?) ;; TODO
;; samples is for generated data only
(s/def ::samples (s/coll-of any? :min-count 0 :kind set?))
(s/def ::failing-samples ::samples)
(s/def ::expression string?)
(s/def ::original-expression ::form)
(s/def ::literal-value ::original-expression)
(s/def ::problem-type (s/and qualified-keyword?
                        (comp #{"error" "warning" "info" "hint"} namespace)))
(s/def ::message-params (s/every-kv keyword? some?
                          :gen-max 3))
(s/def ::file string?)
(s/def ::source string?)
(s/def ::line-start posint?)
(s/def ::line-end posint?)
(s/def ::column-start posint?)
(s/def ::column-end posint?)
(s/def ::key (s/or
               :offset int?
               :typed-key qualified-keyword?
               ;:homogenous ::homogenous
               :arbitrary any?))
(s/def ::positional-types (s/map-of ::key ::type-description))
(s/def ::recursive-description (s/keys :req [::positional-types]))
;; NOTE: We can use a generated sample to in turn generate a recursive description
;; NOTE unknown -> dont know, keep checking, dont report
(s/def ::unknown-expression ::form)
(s/def ::type-description (s/or
                            :unknown (s/keys :req [::unknown-expression])
                            :function ::lambda
                            :value (s/keys :opt [::spec
                                                 ;::recursive-description
                                                 ::type
                                                 ::samples
                                                 ::literal-value
                                                 ::original-expression])))
(s/def ::expected (s/keys :req [(or ::spec ::type)]))
(s/def ::actual (s/keys :opt [::type-description ::failing-samples]))
(s/def ::fn-ref (s/with-gen fn? #(gen/let [any gen/any] (constantly any))))
(s/def ::arglist (s/with-gen (s/or :vector vector?
                               :quoted-vector (s/cat :quote #{'quote}
                                                :symbols vector?))
                   #(gen/vector gen/symbol)))
(s/def ::predicate (s/with-gen fn? gen-predicate))
(s/def ::argument-predicates (s/coll-of ::predicate :kind vector?))
(s/def ::argument-types (s/coll-of ::type :kind vector?))
(s/def ::return-type ::type)
(s/def ::argument-specs (s/coll-of ::spec :kind vector?))
(s/def ::return-spec ::spec)
(s/def ::quoted.argument-specs (s/coll-of ::form :kind vector?))
(s/def ::quoted.return-spec ::form)
(s/def ::return-predicates (s/coll-of ::predicate :kind vector?))
(s/def ::generator (s/with-gen gen/generator? #(gen/return gen/any)))
(s/def ::metadata map?)
(s/def ::gspec (s/keys :req [::return-spec ::return-type]
                 :opt [::argument-specs ::argument-types
                       ::metadata ::generator
                       ::argument-predicates ::return-predicates]))
(s/def ::arity-detail (s/keys :req [::arglist ::gspec]))
(s/def ::arity (conj (set (range (inc 20))) :n))
(s/def ::arities (s/every-kv ::arity ::arity-detail
                   :gen-max 3))
(s/def ::last-changed posint?)
(s/def ::last-checked posint?)
(s/def ::last-seen posint?)
(s/def ::env->fn (s/with-gen fn? #(gen/let [any gen/any] (fn [env] (constantly any)))))
(s/def ::lambda-name simple-symbol?)
(s/def ::lambda (s/keys :req [(or ::fn-ref ::env->fn) ::arities] :opt [::lambda-name]))
(s/def ::lambdas (s/every-kv symbol? ::lambda
                   :gen-max 3))
(s/def ::fn-name symbol?)
(s/def ::var-name qualified-symbol?)
(s/def ::function (s/keys :req [(or ::fn-name ::var-name) ::fn-ref ::arities]))
(s/def ::value any?)
(s/def ::class? boolean?)
(s/def ::extern-name symbol?)
(s/def ::extern (s/keys :req [::extern-name] :opt [::class? ::value]))
(s/def ::location (s/keys
                    :req [::line-start ::column-start]
                    :opt [::line-end ::column-end]))
(s/def ::checking-file string?)
(s/def ::checking-sym simple-symbol?)
(s/def ::current-form ::form)
(s/def ::current-ns string?)
(s/def ::local-symbols (s/every-kv symbol? ::type-description
                         :gen-max 10))
(s/def ::externs-registry (s/every-kv string?
                            (s/every-kv symbol?
                              (s/every-kv symbol? ::extern
                                :gen-max 10)
                              :gen-max 10)
                            :gen-max 10))
(s/def ::spec-registry (s/every-kv ::form ::spec
                         :gen-max 20))
(s/def ::external-function-registry (s/every-kv qualified-symbol? ::function
                                      :gen-max 20))
(s/def ::function-registry (s/every-kv string?
                             (s/every-kv symbol? ::function
                               :gen-max 20)
                             :gen-max 10))
(s/def ::env (s/keys
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
  [arities args]
  [::arities (s/coll-of any?) => ::arity-detail]
  (get arities (count args)
    (get arities :n
      ;; TODO: WIP
      (get arities (first (sort (keys (dissoc arities :n))))))))

(defn fix-kw-nss [x]
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
   (let [spec-registry (->> @gr.externs/spec-registry
                         (fix-kw-nss)
                         (#(resolve-quoted-specs % %)))]
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
  [::env symbol? => symbol?]
  (cljc-rewrite-sym-ns
    (get-in env [::externs-registry (::current-ns env)
                 (::checking-sym env) sym ::extern-name]
      #_:or sym)))

(>defn function-detail [env sym]
  [::env symbol? => (? ::function)]
  (let [qsym (qualify-extern env sym)
        NS   (or (namespace qsym) (::current-ns env))
        SYM  (cond-> sym (namespace qsym)
               (-> name symbol))]
    (get-in env [::function-registry NS SYM])))

(>defn external-function-detail [env sym]
  [::env symbol? => (? ::function)]
  (let [sym (if (qualified-symbol? sym)
              (cljc-rewrite-sym-ns sym)
              (qualify-extern env sym))]
    (get-in env [::external-function-registry sym])))

(>defn symbol-detail [env sym]
  [::env symbol? => (? ::type-description)]
  (or
    (get-in env [::local-symbols sym])
    (get-in env [::externs-registry (::current-ns env) sym ::type-description])))

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

(defonce bindings (atom []))

(defn- clear-bindings-by-file [binds file]
  ($/setval [$/ALL ($/pred (comp (partial = file) ::file))]
    $/NONE binds))

(defn clear-bindings!
  ([] (reset! bindings []))
  ([file] (swap! bindings clear-bindings-by-file file)))

(>defn record-binding!
  "Report a type description for the given simple symbol."
  [env sym td]
  [::env simple-symbol? ::type-description => nil?]
  (let [env (update-location env (meta sym))
        bind (merge td (::location env)
               {::file (::checking-file env)
                ::problem-type :hint/binding-type-info
                ::original-expression sym})]
    (log/debug :record-binding! bind)
    (swap! bindings conj bind)
    nil))

;; ========== PROBLEMS ==========

(s/def ::problem (s/keys
                  :req [::original-expression ::problem-type
                        ::file ::line-start]
                  :opt [::expected ::actual
                        ::column-start ::column-end
                        ::line-end ::message-params]))
(s/def ::error (s/and ::problem (comp #{"error"} namespace ::problem-type)))
(s/def ::warning (s/and ::problem (comp #{"warning"} namespace ::problem-type)))
(s/def ::info (s/and ::problem (comp #{"info"} namespace ::problem-type)))
(s/def ::hint (s/and ::problem (comp #{"hint"} namespace ::problem-type)))
(s/def ::problems (s/coll-of ::problem :kind vector? :gen-max 10))

(defonce problems (atom []))

(>defn env-location [env]
  [::env => map?]
  (merge (::location env)
    {::file (::checking-file env)
     ::sym  (::checking-sym env)
     ::NS   (::current-ns env)}))

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
   (log/info :record-error! (env-location env) "\n" error)
   (swap! problems conj
     (merge error (env-location env)))
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
   (swap! problems conj
     (merge warning (env-location env)))
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
   (swap! problems conj
     (merge info (env-location env)))
   nil))

(defn- clear-problems-by-file [probs file]
  ($/setval [$/ALL ($/pred (comp (partial = file) ::file))]
    $/NONE probs))

(defn clear-problems!
  ([] (reset! problems []))
  ([file] (swap! problems clear-problems-by-file file)))
