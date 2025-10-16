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

(ns com.fulcrologic.copilot.artifacts
  #?(:cljs (:require-macros clojure.test.check.generators))
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.copilot.analysis.spec :as cp.spec]
    [com.fulcrologic.copilot.analysis.purity-data :as purity-data]
    [com.fulcrologic.copilot.analytics :as cp.analytics]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.guardrails.registry :as gr.reg]
    [com.fulcrologic.guardrails.impl.externs :as gr.externs]
    [com.rpl.specter :as $]
    [com.fulcrologicpro.taoensso.timbre :as log]))

;; ========== CLJC SYM REWRITE ==========

(defmulti cljc-rewrite-sym-ns-mm identity)
(defmethod cljc-rewrite-sym-ns-mm "cljs.core" [ns] "clojure.core")
(defmethod cljc-rewrite-sym-ns-mm :default [ns] ns)

(>defn cljc-rewrite-sym-ns [sym]
  [symbol? => symbol?]
  (symbol (cljc-rewrite-sym-ns-mm (namespace sym))
    (name sym)))

;; ========== HELPERS ============

(defn unwrap-meta [x]
  ($/transform ($/walker :com.fulcrologic.copilot/meta-wrapper?) :value x))

;; ========== ARTIFACTS ==========

(def posint?
  (s/with-gen pos-int?
    #(gen/such-that pos? (gen/int))))

(def gen-predicate #(gen/return (fn [& _] (rand-nth [true false]))))
#_(map (fn [pf] (pf)) (gen/sample (gen-predicate)))

(s/def ::spec (s/with-gen
                (s/or
                  :spec-name qualified-keyword?
                  :spec-object #(s/spec? %)
                  :predicate ifn?)
                gen-predicate))
(s/def ::type string?)
(s/def ::form any?)                                         ;; TODO
;; samples is for generated data only
(s/def ::samples (s/coll-of any? :min-count 0 :kind set?))
(s/def ::failing-samples ::samples)
(s/def ::expression string?)
(s/def ::original-expression ::form)
(s/def ::level (s/int-in 1 12))
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
                            :path-based (s/keys :req [::execution-paths])
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
(s/def ::lambdas (s/every-kv (s/tuple posint? posint?) ::lambda
                   :gen-max 3))
(s/def ::fn-name symbol?)
(s/def ::var-name qualified-symbol?)
(s/def ::function (s/keys :req [(or ::fn-name ::var-name) ::fn-ref ::arities]))
(s/def ::value any?)
(s/def ::macro? boolean?)
(s/def ::extern-name symbol?)
(s/def ::extern (s/keys :req [::extern-name] :opt [::macro? ::value]))
(s/def ::location (s/keys
                    :req [::line-start ::column-start]
                    :opt [::line-end ::column-end]))

;; ========== PATH-BASED ANALYSIS SPECS ==========
;; Path conditions represent branch conditions taken during execution
(s/def ::condition-id nat-int?)
(s/def ::condition-expression ::form)
(s/def ::condition-location ::location)
(s/def ::determined? boolean?)                              ;; Can we partition samples?
(s/def ::branch keyword?)                                   ;; :then, :else, :branch-0, etc.
(s/def ::condition-value boolean?)                          ;; For determined paths, the actual condition result

(s/def ::path-condition
  (s/keys :req [::condition-id
                ::condition-expression
                ::condition-location
                ::determined?
                ::branch]
    :opt [::condition-value]))

(s/def ::conditions (s/coll-of ::path-condition :kind vector?))

;; An execution path tracks samples with their conditions and bindings
(s/def ::path-id nat-int?)
(s/def ::path-bindings (s/map-of symbol? ::samples))        ;; Bound variables with their samples in this path
(s/def ::merged? boolean?)                                  ;; Path was merged from multiple paths
(s/def ::condition-sets (s/coll-of ::conditions :kind vector?)) ;; For merged paths

(s/def ::execution-path
  (s/keys :req [::path-id
                ::conditions
                ::samples
                ::path-bindings]
    :opt [::merged? ::condition-sets]))

(s/def ::execution-paths (s/coll-of ::execution-path :min-count 1 :kind vector?))
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
;; Path analysis tracking
(s/def ::next-condition-id nat-int?)
(s/def ::next-path-id nat-int?)

(s/def ::env (s/keys
               :req [::function-registry]
               :opt [::external-function-registry
                     ::externs-registry
                     ::spec-registry
                     ::local-symbols
                     ::checking-sym
                     ::current-form
                     ::current-ns
                     ::location
                     ::aliases
                     ::refers
                     ::next-condition-id
                     ::next-path-id]))

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
  (get (::spec-registry env) (unwrap-meta quoted-spec)))

(defn resolve-quoted-specs [env registry]
  (let [RESOLVE (fn [m]
                  (cond-> m
                    (::quoted.argument-specs m)
                    #_=> (assoc ::argument-specs
                                (mapv (partial lookup-spec env)
                                  (::quoted.argument-specs m)))
                    (::quoted.return-spec m)
                    #_=> (assoc ::return-spec
                                (lookup-spec env
                                  (::quoted.return-spec m)))))]
    ($/transform [($/walker (some-fn ::quoted.argument-specs ::quoted.return-spec))]
      RESOLVE registry)))

(>defn build-env
  [{:keys [NS meta file aliases refers]}]
  [map? => ::env]
  (let [env {::aliases          aliases
             ::checking-file    file
             ::current-ns       NS
             ::ns-meta          meta
             ::externs-registry (fix-kw-nss @gr.externs/externs-registry)
             ::refers           refers
             ::spec-registry    (fix-kw-nss @gr.externs/spec-registry)}]
    (-> env
      (merge {::external-function-registry
              (->> @gr.externs/external-function-registry
                (fix-kw-nss)
                (resolve-quoted-specs env))
              ::function-registry
              (->> @gr.externs/function-registry
                (fix-kw-nss)
                (resolve-quoted-specs env))})
      (cp.spec/with-spec-impl :clojure.spec.alpha))))

(>defn qualify-extern
  [env sym]
  [::env symbol? => symbol?]
  (cljc-rewrite-sym-ns
    (or
      (get-in env [::externs-registry (::current-ns env)
                   (::checking-sym env) sym ::extern-name])
      (when-let [fq-ns (some->> sym namespace symbol
                         (get (::aliases env)))]
        (symbol (str fq-ns) (name sym)))
      (get (::refers env) sym)
      sym)))

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
    (get-in env [::external-function-registry sym]
      (when-not (namespace sym)
        (external-function-detail env
          (symbol "clojure.core" (name sym)))))))

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

;; ========== PATH-BASED HELPERS ==========

(>defn path-based?
  "Check if type-description uses execution paths"
  [td]
  [::type-description => boolean?]
  (contains? td ::execution-paths))

(>defn ensure-path-based
  "Convert old-style samples to path-based form (single path, no conditions).
If already path-based, returns as-is."
  [td]
  [::type-description => ::type-description]
  (if (path-based? td)
    td
    (if-let [samples (seq (::samples td))]
      (assoc td ::execution-paths
                [{::path-id       0
                  ::conditions    []
                  ::samples       (set samples)
                  ::path-bindings {}}])
      td)))

(>defn extract-all-samples
  "Get all samples across all paths (loses path info).
Returns a set of all samples found in any path."
  [td]
  [::type-description => ::samples]
  (if (path-based? td)
    (reduce set/union #{} (map ::samples (::execution-paths td)))
    (::samples td #{})))

(>defn create-single-path
  "Create a simple execution path with samples and bindings"
  [samples bindings]
  [::samples (s/map-of symbol? ::samples) => ::execution-path]
  {::path-id       0
   ::conditions    []
   ::samples       samples
   ::path-bindings bindings})

(>defn add-condition
  "Add a condition to an execution path's condition list"
  [path condition-id condition-expr location determined? branch]
  [::execution-path int? any? ::location boolean? ::branch => ::execution-path]
  (update path ::conditions conj
    {::condition-id         condition-id
     ::condition-expression condition-expr
     ::condition-location   location
     ::determined?          determined?
     ::branch               branch}))

(>defn add-determined-condition
  "Add a determined condition to an execution path (we know the partition)"
  [path condition-id condition-expr location value branch]
  [::execution-path int? any? ::location boolean? ::branch => ::execution-path]
  (update path ::conditions conj
    {::condition-id         condition-id
     ::condition-expression condition-expr
     ::condition-location   location
     ::determined?          true
     ::condition-value      value
     ::branch               branch}))

(>defn add-undetermined-condition
  "Add an undetermined condition to an execution path (superposition)"
  [path condition-id condition-expr location branch]
  [::execution-path int? any? ::location ::branch => ::execution-path]
  (update path ::conditions conj
    {::condition-id         condition-id
     ::condition-expression condition-expr
     ::condition-location   location
     ::determined?          false
     ::branch               branch}))

(>defn update-binding-with-samples
  "Update a single symbol binding in the environment with filtered samples.
Creates a path-based type-description with a single path containing the filtered samples."
  [env sym filtered-samples]
  [::env symbol? ::samples => ::env]
  (if (empty? filtered-samples)
    env
    (let [current-td (get-in env [::local-symbols sym])
          new-td     {::execution-paths
                      [{::path-id       0
                        ::conditions    []
                        ::samples       filtered-samples
                        ::path-bindings {}}]}]
      (assoc-in env [::local-symbols sym] new-td))))

(>defn update-env-with-path-bindings
  "Update environment with filtered samples for all symbols in path-bindings.
Each symbol in path-bindings is updated in the environment's local-symbols
to have a new path-based type-description with the filtered samples."
  [env path-bindings]
  [::env (s/map-of symbol? ::samples) => ::env]
  (reduce-kv
    (fn [env' sym samples]
      (update-binding-with-samples env' sym samples))
    env
    path-bindings))

;; ========== SAMPLE PARTITIONING FOR PATH-BASED ANALYSIS ==========

(>defn resolve-pure-function
  "Resolve a function symbol to an executable function or its pure-mock.
Returns a function that can be safely called during analysis,
or nil if the function cannot be resolved or is not pure.

Attempts to resolve in this order:
1. Check function/external-function registries for pure-mock
2. Check function/external-function registries for pure? metadata
3. Fallback: If function is pure (per multimethod), try to resolve directly"
  [env fn-sym]
  [::env symbol? => (? fn?)]
  (if-let [fn-detail (or (function-detail env fn-sym)
                       (external-function-detail env fn-sym))]
    ;; Function is in registry - check for pure-mock or pure? metadata
    (let [arities   (::arities fn-detail)
          ;; Check for pure-mock in metadata
          pure-mock (some (fn [[_arity arity-detail]]
                            (when-let [gspec (::gspec arity-detail)]
                              (let [metadata (::metadata gspec)]
                                (:pure-mock metadata))))
                      arities)
          ;; Check if function is marked as pure
          is-pure?  (boolean
                      (some (fn [[_arity arity-detail]]
                              (when-let [gspec (::gspec arity-detail)]
                                (let [metadata (::metadata gspec)]
                                  (or (:pure? metadata)
                                    (:pure metadata)))))
                        arities))]
      (cond
        ;; If there's a pure-mock, use it
        pure-mock
        pure-mock

        ;; If the function is marked as pure, return the actual function
        is-pure?
        (or (::fn-ref fn-detail)
          (when-let [env->fn (::env->fn fn-detail)]
            (env->fn env)))

        ;; Otherwise, return nil (not safe to call)
        :else
        nil))

    ;; Function not in registry - check if it's pure via multimethod and try to resolve
    (when (purity-data/known-pure-function? fn-sym)
      #?(:clj
         (try
           (resolve fn-sym)
           (catch Exception e
             (log/debug "Failed to resolve pure function" fn-sym e)
             nil))
         :cljs
         nil))))

(>defn eval-condition
  "Evaluate a condition expression with a specific sample value for a symbol.
Returns {:result boolean?, :error? boolean?, :error any?}

The condition is evaluated in a mini-interpreter that:
- Resolves local symbols to their sample values
- Resolves function symbols to pure functions or pure-mocks
- Evaluates the condition and returns the result
- Treats 0 as falsey (in addition to false and nil)

If evaluation fails (e.g., function not available, error during eval),
returns {:error? true, :error <exception>}"
  [env condition-expr symbol-bindings]
  [::env any? (s/map-of symbol? any?) => (s/keys :req-un [::result ::error?])]
  (try
    (letfn [(eval-expr [expr]
              (cond
                ;; Literal values
                (or (string? expr) (number? expr) (boolean? expr) (nil? expr)
                  (keyword? expr))
                expr

                ;; Symbol lookup - check bindings first, then resolve as function
                (symbol? expr)
                (if (contains? symbol-bindings expr)
                  (get symbol-bindings expr)
                  (or (resolve-pure-function env expr)
                    (throw (ex-info "Cannot resolve symbol" {:symbol expr}))))

                ;; Collections
                (vector? expr) (mapv eval-expr expr)
                (map? expr) (into {} (map (fn [[k v]] [(eval-expr k) (eval-expr v)]) expr))
                (set? expr) (set (map eval-expr expr))

                ;; Function calls
                (seq? expr)
                (let [[fn-sym & args] expr
                      f (if (contains? symbol-bindings fn-sym)
                          (get symbol-bindings fn-sym)
                          (resolve-pure-function env fn-sym))]
                  (if-not f
                    (throw (ex-info "Cannot resolve function" {:fn-sym fn-sym}))
                    (apply f (map eval-expr args))))

                :else
                (throw (ex-info "Cannot evaluate expression" {:expr expr}))))
            (truthy? [v]
              ;; Custom truthiness: false, nil, and 0 are falsey
              (not (or (false? v) (nil? v) (and (number? v) (zero? v)))))]
      {:result (truthy? (eval-expr condition-expr))
       :error? false})
    (catch #?(:clj Throwable :cljs :default) e
      {:result false
       :error? true
       :error  e})))

(>defn partition-samples-by-condition
  "Partition samples based on whether they satisfy a condition.

Given:
- env: analysis environment
- condition-expr: the condition expression to evaluate
- binding-symbol: the symbol being tested in the condition
- samples: set of sample values to partition

Returns:
{:true-samples #{...}    ; Samples where condition is true
:false-samples #{...}   ; Samples where condition is false
:undetermined-samples #{...}  ; Samples where condition couldn't be evaluated
:determined? boolean}   ; true if all samples could be evaluated"
  [env condition-expr binding-symbol samples]
  [::env any? symbol? ::samples
   => (s/keys :req-un [::true-samples ::false-samples ::undetermined-samples ::determined?])]
  (let [results (group-by
                  (fn [sample]
                    (let [bindings {binding-symbol sample}
                          {:keys [result error?]} (eval-condition env condition-expr bindings)]
                      (cond
                        error? :undetermined
                        result :true
                        :else :false)))
                  samples)]
    {:true-samples         (set (get results :true []))
     :false-samples        (set (get results :false []))
     :undetermined-samples (set (get results :undetermined []))
     :determined?          (empty? (get results :undetermined []))}))

;; ========== PATH DEDUPLICATION AND LIMITS ==========

(def ^:dynamic *max-paths* 500)
(def ^:dynamic *max-samples-per-path* 20)

(>defn limit-samples
  "Limit samples to a maximum count, randomly sampling if needed"
  [samples max-count]
  [::samples pos-int? => ::samples]
  (if (<= (count samples) max-count)
    samples
    (set (take max-count (shuffle (vec samples))))))

(>defn deduplicate-paths
  "Merge execution paths that have identical samples.
Paths with the same samples can be merged, combining their conditions.
Returns a vector of deduplicated paths."
  [paths]
  [::execution-paths => ::execution-paths]
  (let [grouped (group-by ::samples paths)]
    (vec
      (map (fn [[samples paths-with-same-samples]]
             (if (= 1 (count paths-with-same-samples))
               ;; Single path, return as-is
               (first paths-with-same-samples)
               ;; Multiple paths with same samples, merge them
               (let [all-conditions  (mapv ::conditions paths-with-same-samples)
                     merged-bindings (apply merge (map ::path-bindings paths-with-same-samples))]
                 {::path-id        (::path-id (first paths-with-same-samples))
                  ::conditions     (::conditions (first paths-with-same-samples))
                  ::samples        samples
                  ::path-bindings  merged-bindings
                  ::merged?        true
                  ::condition-sets all-conditions})))
        grouped))))

(>defn limit-paths
  "Limit the number of execution paths to prevent explosion.
If there are too many paths, randomly select a subset."
  [paths max-paths]
  [::execution-paths pos-int? => ::execution-paths]
  (if (<= (count paths) max-paths)
    paths
    (do
      (log/warn "Path explosion detected:" (count paths) "paths, limiting to" max-paths)
      (vec (take max-paths (shuffle paths))))))

(>defn apply-path-limits
  "Apply both deduplication and size limits to execution paths.

Steps:
1. Deduplicate paths with identical samples
2. Limit samples per path to max-samples-per-path
3. Limit total paths to max-paths

Uses dynamic vars *max-paths* and *max-samples-per-path* for configuration."
  [paths]
  [::execution-paths => ::execution-paths]
  (-> paths
    deduplicate-paths
    (->> (mapv #(update % ::samples limit-samples *max-samples-per-path*)))
    (limit-paths *max-paths*)))

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

(>defn sync-location
  [env sexpr]
  [::env any? => ::env]
  (let [loc-meta (or (meta sexpr) (:metadata sexpr) nil)]
    (update-location env loc-meta)))

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
  (let [env  (update-location env (meta sym))
        bind (merge td (::location env)
               {::file                (::checking-file env)
                ::problem-type        :hint/binding-type-info
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

(>defn env-location [env problem]
  [::env map? => map?]
  (let [env (sync-location env (::original-expression problem))]
    (merge (::location env)
      {::file (::checking-file env)
       ::sym  (::checking-sym env)
       ::NS   (::current-ns env)})))

(defn record-problem! [env problem]
  (log/debug :record-problem! problem)
  (cp.analytics/record-problem! env problem)
  (swap! problems conj
    (merge problem (env-location env problem))))

(>defn record-error!
  ([env original-expression problem-type]
   [::env ::original-expression ::problem-type => nil?]
   (record-error! env original-expression problem-type {}))
  ([env original-expression problem-type message-params]
   [::env ::original-expression ::problem-type ::message-params => nil?]
   (record-error! env {::problem-type        problem-type
                       ::original-expression original-expression
                       ::message-params      message-params}))
  ([env error]
   [::env (s/keys :req [::problem-type ::original-expression]) => nil?]
   (record-problem! env error)
   nil))

(>defn record-warning!
  ([env original-expression problem-type]
   [::env ::original-expression ::problem-type => nil?]
   (record-warning! env original-expression problem-type {}))
  ([env original-expression problem-type message-params]
   [::env ::original-expression ::problem-type ::message-params => nil?]
   (record-warning! env {::problem-type        problem-type
                         ::original-expression original-expression
                         ::message-params      message-params}))
  ([env warning]
   [::env (s/keys :req [::problem-type ::original-expression]) => nil?]
   (record-problem! env warning)
   nil))

(>defn record-info!
  ([env original-expression problem-type]
   [::env ::original-expression ::problem-type => nil?]
   (record-info! env original-expression problem-type {}))
  ([env original-expression problem-type message-params]
   [::env ::original-expression ::problem-type ::message-params => nil?]
   (record-info! env {::problem-type        problem-type
                      ::original-expression original-expression
                      ::message-params      message-params}))
  ([env info]
   [::env (s/keys :req [::problem-type ::original-expression]) => nil?]
   (record-problem! env info)
   nil))

(defn- clear-problems-by-file [probs file]
  ($/setval [$/ALL ($/pred (comp (partial = file) ::file))]
    $/NONE probs))

(defn clear-problems!
  ([] (reset! problems []))
  ([file] (swap! problems clear-problems-by-file file)))
