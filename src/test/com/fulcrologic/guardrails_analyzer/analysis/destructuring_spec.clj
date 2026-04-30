(ns com.fulcrologic.guardrails-analyzer.analysis.destructuring-spec
  (:require
   [clojure.spec.alpha :as s]
   [com.fulcrologic.guardrails-analyzer.analysis.destructuring :as cp.dest]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

;; ----------------------------------------------------------------------------
;; Specs registered to exercise ?validate-samples! lookups.
;; ----------------------------------------------------------------------------

(s/def ::a int?)

;; Helper to construct a real MapEntry (destr-map-entry! requires map-entry?).
(defn- map-entry [k v]
  (clojure.lang.MapEntry. k v))

;; ============================================================================
;; -destructure! - dispatch and unknown handling
;; ============================================================================

(specification "-destructure! returns no bindings when value-type-desc is unknown"
               (let [env (tf/test-env)]
                 (assertions
                  "Unknown expressions short-circuit and produce an empty bindings map"
                  (cp.dest/-destructure! env 'x {::cp.art/unknown-expression '(some-call)})
                  => {})))

(specification "-destructure! binds a plain symbol to the value type description"
               (let [env (tf/test-env)
                     td  {::cp.art/samples #{1 2}}
                     res (cp.dest/-destructure! env 'x td)]
                 (assertions
                  "produces exactly one binding entry"
                  (count res) => 1
                  "the bound symbol carries the original samples"
                  (::cp.art/samples (get res 'x)) => #{1 2}
                  "the bound symbol records its original-expression as itself"
                  (::cp.art/original-expression (get res 'x)) => 'x)))

(specification "-destructure! dispatches by bind-sexpr shape"
               (let [env (tf/test-env)]
                 (component "vector bind-sexpr is destructured positionally"
                            (assertions
                             "[a] extracts (nth 0) of each sample for symbol a"
                             (cp.dest/-destructure! env '[a] {::cp.art/samples #{[10] [20]}})
                             => {'a {::cp.art/samples             #{10 20}
                                     ::cp.art/original-expression 'a}}))

                 (component "map bind-sexpr is destructured by entry"
                            (assertions
                             "{a :a} extracts samples by simple keyword"
                             (cp.dest/-destructure! env '{a :a} {::cp.art/samples #{{:a 1} {:a 2}}})
                             => {'a {::cp.art/samples             #{1 2}
                                     ::cp.art/original-expression 'a}}))))

;; ============================================================================
;; destr-vector! - positional, rest, :as, nested
;; ============================================================================

(specification "destr-vector! - positional bindings"
               (let [env (tf/test-env)]
                 (assertions
                  "empty vector produces no bindings"
                  (cp.dest/destr-vector! env '[] {::cp.art/samples #{[1 2]}})
                  => {}

                  "single positional symbol extracts (nth 0) of each sample"
                  (cp.dest/destr-vector! env '[a] {::cp.art/samples #{[1] [2]}})
                  => {'a {::cp.art/samples             #{1 2}
                          ::cp.art/original-expression 'a}}

                  "multiple positional symbols extract by their respective indices"
                  (cp.dest/destr-vector! env '[a b] {::cp.art/samples #{[10 20]}})
                  => {'a {::cp.art/samples             #{10}
                          ::cp.art/original-expression 'a}
                      'b {::cp.art/samples             #{20}
                          ::cp.art/original-expression 'b}}

                  "samples shorter than the symbol position yield nil for that symbol"
                  (cp.dest/destr-vector! env '[a b] {::cp.art/samples #{[1]}})
                  => {'a {::cp.art/samples             #{1}
                          ::cp.art/original-expression 'a}
                      'b {::cp.art/samples             #{nil}
                          ::cp.art/original-expression 'b}})))

(specification "destr-vector! - rest argument (&)"
               (let [env (tf/test-env)]
                 (assertions
                  "rest binding contains samples with the leading positional elements dropped"
                  (-> (cp.dest/destr-vector! env '[a b & rest] {::cp.art/samples #{[1 2 3 4]}})
                      (get 'rest)
                      ::cp.art/samples)
                  => #{'(3 4)}

                  "with no positional symbols the rest binding gets each full sample (drop 0)"
                  (-> (cp.dest/destr-vector! env '[& rest] {::cp.art/samples #{[1 2 3]}})
                      (get 'rest)
                      ::cp.art/samples)
                  => #{'(1 2 3)})))

(specification "destr-vector! - :as binding"
               (let [env (tf/test-env)
                     td  {::cp.art/samples #{[1 2 3]}}]
                 (assertions
                  "binds the entire passed-in type description to the :as symbol"
                  (get (cp.dest/destr-vector! env '[:as v] td) 'v)
                  => td

                  "with positional bindings, :as still binds the unmodified td to v"
                  (get (cp.dest/destr-vector! env '[a b :as v] td) 'v)
                  => td)))

(specification "destr-vector! - nested vectors"
               (let [env (tf/test-env)]
                 (assertions
                  "[[a b] c] recursively destructures the inner vector"
                  (cp.dest/destr-vector! env '[[a b] c] {::cp.art/samples #{[[1 2] 3]}})
                  => {'a {::cp.art/samples             #{1}
                          ::cp.art/original-expression 'a}
                      'b {::cp.art/samples             #{2}
                          ::cp.art/original-expression 'b}
                      'c {::cp.art/samples             #{3}
                          ::cp.art/original-expression 'c}})))

;; ============================================================================
;; destr-map-entry! - :keys variants, explicit map binding, :as, fallback
;; ============================================================================

(specification "destr-map-entry! - plain :keys returns no bindings"
               (let [env (tf/test-env)]
                 (assertions
                  "An entry whose key is the simple keyword :keys yields {}"
                  (cp.dest/destr-map-entry! env (map-entry :keys '[a b]) {::cp.art/samples #{}})
                  => {})))

(specification "destr-map-entry! - namespaced ::ns/keys produces a binding per symbol"
               ;; The fn's qualified-keys branch matches when (= "keys" (name k))
               ;; and k is a qualified keyword. The spec-kw becomes
               ;; (keyword (namespace k) (str sym)) — here ::cp.art/a.
               (let [env (tf/test-env)
                     res (cp.dest/destr-map-entry! env
                                                   (map-entry ::cp.art/keys '[a])
                                                   {::cp.art/samples #{{::cp.art/a 1}
                                                                       {::cp.art/a 2}}})]
                 (assertions
                  "produces one binding tuple per symbol in the keys vector"
                  (count res) => 1

                  "binding symbol matches the symbol in the keys vector"
                  (-> res first first) => 'a

                  "binding samples are extracted via the constructed spec keyword (ns/sym)"
                  (-> res first second ::cp.art/samples) => #{1 2}

                  "binding records the constructed spec keyword as the spec"
                  (-> res first second ::cp.art/spec) => ::cp.art/a

                  "binding records the constructed type as (pr-str spec-kw)"
                  (-> res first second ::cp.art/type) => (pr-str ::cp.art/a))))

(specification "destr-map-entry! - explicit map binding {x :x} (simple keyword value)"
               (let [env (tf/test-env)]
                 (assertions
                  "extracts samples via the simple keyword and binds them to the symbol"
                  (cp.dest/destr-map-entry! env
                                            (map-entry 'x :x)
                                            {::cp.art/samples #{{:x 10} {:x 20}}})
                  => {'x {::cp.art/samples             #{10 20}
                          ::cp.art/original-expression 'x}})))

(specification "destr-map-entry! - explicit map binding {x ::a} (qualified keyword value)"
               (let [env (tf/test-env)
                     res (cp.dest/destr-map-entry! env
                                                   (map-entry 'x ::a)
                                                   {::cp.art/samples #{{::a 1} {::a 2}}})
                     bound (get res 'x)]
                 (assertions
                  "extracts samples via the qualified keyword"
                  (::cp.art/samples bound) => #{1 2}

                  "tags the binding with the qualified spec keyword"
                  (::cp.art/spec bound) => ::a

                  "tags the binding with (pr-str spec-kw) as ::type"
                  (::cp.art/type bound) => (pr-str ::a)

                  "rebinds the symbol's original-expression to the symbol itself"
                  (::cp.art/original-expression bound) => 'x)))

(specification "destr-map-entry! - {:as v} captures the entire value"
               (let [env (tf/test-env)
                     td  {::cp.art/samples #{{:a 1 :b 2}}}]
                 (assertions
                  "returns a single tuple binding v to td with v as the original-expression"
                  (cp.dest/destr-map-entry! env (map-entry :as 'whole) td)
                  => [['whole {::cp.art/samples             #{{:a 1 :b 2}}
                               ::cp.art/original-expression 'whole}]])))

(specification "destr-map-entry! - non-matching key/value yields no binding"
               (let [env (tf/test-env)]
                 (assertions
                  "Returns [] for entries that match none of the recognised shapes"
                  (cp.dest/destr-map-entry! env (map-entry :unknown 'x) {::cp.art/samples #{}})
                  => [])))

;; ============================================================================
;; destructure! - records bindings and handles internal exceptions
;; ============================================================================

(specification "destructure! records each resulting binding via record-binding!"
               (let [captured (tf/capture-bindings
                               cp.dest/destructure!
                               (tf/test-env)
                               '[a b]
                               {::cp.art/samples #{[1 2]}})]
                 (assertions
                  "one binding is recorded per symbol resolved by destructuring"
                  (count captured) => 2

                  "captured bindings carry the per-position extracted samples"
                  (set (map ::cp.art/samples captured)) => #{#{1} #{2}})))

(specification "destructure! returns the bindings map produced by -destructure!"
               (let [res (cp.dest/destructure!
                          (tf/test-env)
                          '[a]
                          {::cp.art/samples #{[7]}})]
                 (assertions
                  "the returned map contains every resolved symbol as a key"
                  (set (keys res)) => #{'a}

                  "values are the type descriptions produced by the recursive walk"
                  (::cp.art/samples (get res 'a)) => #{7})))

(specification "destructure! catches internal exceptions and returns {}"
               (with-redefs [cp.art/record-binding! (fn [& _]
                                                      (throw (ex-info "boom" {})))]
                 (assertions
                  "an exception thrown while recording bindings is caught (no rethrow)"
                  (cp.dest/destructure! (tf/test-env)
                                        '[a]
                                        {::cp.art/samples #{[1]}})
                  => {})))

;; ============================================================================
;; ?validate-samples! - warnings and errors
;; ============================================================================

(specification "?validate-samples! records a missing-spec warning when no spec is registered"
               (let [warnings (tf/capture-warnings
                               #'cp.dest/?validate-samples!
                               (tf/test-env)
                               ::nothing-here-mate
                               #{{::nothing-here-mate 1}})]
                 (assertions
                  "records a :warning/qualified-keyword-missing-spec problem"
                  (set (map ::cp.art/problem-type warnings))
                  => #{:warning/qualified-keyword-missing-spec})))

(specification "?validate-samples! records missing-entry warning when key is absent in some samples"
               (let [warnings (tf/capture-warnings
                               #'cp.dest/?validate-samples!
                               (tf/test-env)
                               ::a
                               #{{:other 1}})]
                 (assertions
                  "records a :warning/destructured-map-entry-may-not-be-present problem"
                  (some #{:warning/destructured-map-entry-may-not-be-present}
                        (map ::cp.art/problem-type warnings))
                  => :warning/destructured-map-entry-may-not-be-present)))

(specification "?validate-samples! records spec-failure error when an extracted value fails the spec"
               (let [errors (tf/capture-errors
                             #'cp.dest/?validate-samples!
                             (tf/test-env)
                             ::a
                             #{{::a "not-an-int"}})]
                 (assertions
                  "records a :error/value-failed-spec problem"
                  (set (map ::cp.art/problem-type errors))
                  => #{:error/value-failed-spec})))

(specification "?validate-samples! records nothing for valid samples against a registered spec"
               (let [env      (tf/test-env)
                     warnings (tf/capture-warnings
                               #'cp.dest/?validate-samples!
                               env
                               ::a
                               #{{::a 1} {::a 2}})
                     errors   (tf/capture-errors
                               #'cp.dest/?validate-samples!
                               env
                               ::a
                               #{{::a 1} {::a 2}})]
                 (assertions
                  "no warnings are recorded"
                  warnings => []

                  "no errors are recorded"
                  errors => [])))
