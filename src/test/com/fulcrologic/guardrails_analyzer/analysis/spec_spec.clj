(ns com.fulcrologic.guardrails-analyzer.analysis.spec-spec
  (:require
   [clojure.spec.alpha :as s]
   [com.fulcrologic.guardrails-analyzer.analysis.spec :as cp.spec]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

;; Local specs registered for tests that need real specs in the spec registry
(s/def ::test-int int?)
(s/def ::test-string string?)

;; ============================================================================
;; make-size-range-seq
;; ============================================================================

(specification "make-size-range-seq"
               (assertions
                "starts at 0"
                (first (cp.spec/make-size-range-seq 10)) => 0

                "repeats each value 5 times before advancing"
                (take 5 (cp.spec/make-size-range-seq 10)) => '(0 0 0 0 0)
                (nth (cp.spec/make-size-range-seq 10) 5) => 1
                (take 5 (drop 5 (cp.spec/make-size-range-seq 10))) => '(1 1 1 1 1)

                "uses (range 0 max-size) as the underlying value sequence"
                (set (take 50 (cp.spec/make-size-range-seq 10))) => #{0 1 2 3 4 5 6 7 8 9}

                "cycles back to 0 after max-size distinct values"
    ;; 3 distinct values * 5 repeats each = 15 elements before cycle restarts
                (nth (cp.spec/make-size-range-seq 3) 15) => 0

                "is lazy/infinite (large takes do not exhaust the seq)"
                (count (take 1000 (cp.spec/make-size-range-seq 5))) => 1000))

;; ============================================================================
;; sample-seq
;; ============================================================================

(specification "sample-seq"
               (component "single-arg form"
                          (let [samples (take 5 (cp.spec/sample-seq (s/gen int?)))]
                            (assertions
                             "produces values that satisfy the spec"
                             (every? int? samples) => true
                             "produces the requested number of samples"
                             (count samples) => 5)))

               (component "two-arg form (explicit max-size)"
                          (let [samples (take 5 (cp.spec/sample-seq (s/gen int?) 20))]
                            (assertions
                             "produces values that satisfy the spec"
                             (every? int? samples) => true
                             "produces the requested number of samples"
                             (count samples) => 5)))

               (component "laziness"
                          (assertions
                           "returns a lazy sequence (a large take of an unrealized seq does not OOM)"
                           (count (take 5000 (cp.spec/sample-seq (s/gen int?)))) => 5000

                           "works with predicate-based generator"
                           (every? int? (take 3 (cp.spec/sample-seq (s/gen int?)))) => true

                           "works with keyword-spec-based generator"
                           (every? int? (take 3 (cp.spec/sample-seq (s/gen ::test-int)))) => true

                           "works with string predicate generator"
                           (every? string? (take 3 (cp.spec/sample-seq (s/gen string?)))) => true)))

;; ============================================================================
;; with-spec-impl
;; ============================================================================

(specification "with-spec-impl"
               (component "creates ClojureSpecAlpha for :clojure.spec.alpha"
                          (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha)
                                impl (::cp.spec/impl env)]
                            (assertions
                             "stores impl under ::cp.spec/impl"
                             (some? impl) => true
                             "satisfies the ISpec protocol"
                             (satisfies? cp.spec/ISpec impl) => true
                             "the impl resolves clojure.spec specs via s/get-spec"
                             (some? (cp.spec/-lookup impl ::test-int)) => true)))

               (component "creates MalliSpec for :malli"
                          (let [env (cp.spec/with-spec-impl {} :malli)
                                impl (::cp.spec/impl env)]
                            (assertions
                             "stores impl under ::cp.spec/impl"
                             (some? impl) => true
                             "satisfies the ISpec protocol"
                             (satisfies? cp.spec/ISpec impl) => true
                             "the impl resolves malli schemas (returns the schema value)"
                             (cp.spec/-lookup impl :int) => :int)))

               (component "falls back to ClojureSpecAlpha for unknown impl type"
                          (let [env (cp.spec/with-spec-impl {} :unknown-type)
                                impl (::cp.spec/impl env)]
                            (assertions
                             "behaves as ClojureSpecAlpha (resolves clojure.spec keyword specs)"
                             (some? (cp.spec/-lookup impl ::test-int)) => true)))

               (component "default options"
                          (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha)
                                opts (:opts (::cp.spec/impl env))]
                            (assertions
                             "default :num-samples is 10"
                             (:num-samples opts) => 10
                             "default :cache-samples? is true"
                             (:cache-samples? opts) => true)))

               (component "user-supplied options override defaults"
                          (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha {:num-samples 5
                                                                                    :cache-samples? false})
                                opts (:opts (::cp.spec/impl env))]
                            (assertions
                             "user :num-samples overrides default"
                             (:num-samples opts) => 5
                             "user :cache-samples? overrides default"
                             (:cache-samples? opts) => false))))

;; ============================================================================
;; with-both-impls
;; ============================================================================

(specification "with-both-impls"
               (let [env (cp.spec/with-both-impls {})]
                 (assertions
                  "active ::impl resolves clojure.spec keyword specs"
                  (some? (cp.spec/-lookup (::cp.spec/impl env) ::test-int)) => true

                  "::malli-impl resolves malli schemas"
                  (cp.spec/-lookup (::cp.spec/malli-impl env) :int) => :int

                  "::spec-impl resolves clojure.spec keyword specs"
                  (some? (cp.spec/-lookup (::cp.spec/spec-impl env) ::test-int)) => true

                  "default :num-samples is 10 in both stored impls"
                  (:num-samples (:opts (::cp.spec/malli-impl env))) => 10
                  (:num-samples (:opts (::cp.spec/spec-impl env))) => 10))

               (component "with user-supplied options"
                          (let [env (cp.spec/with-both-impls {} {:num-samples 7})]
                            (assertions
                             "user :num-samples is applied to both impls"
                             (:num-samples (:opts (::cp.spec/malli-impl env))) => 7
                             (:num-samples (:opts (::cp.spec/spec-impl env))) => 7))))

;; ============================================================================
;; with-spec-system
;; ============================================================================

(specification "with-spec-system"
               (let [env (cp.spec/with-both-impls {})]
                 (component "switching to :malli"
                            (let [switched (cp.spec/with-spec-system env :malli)]
                              (assertions
                               "active ::impl is replaced with the stored malli-impl"
                               (::cp.spec/impl switched) => (::cp.spec/malli-impl env))))

                 (component "switching to nil (default spec system)"
                            (let [switched (cp.spec/with-spec-system env nil)]
                              (assertions
                               "active ::impl is replaced with the stored spec-impl"
                               (::cp.spec/impl switched) => (::cp.spec/spec-impl env))))

                 (component "switching to :org.clojure/spec1"
                            (let [switched (cp.spec/with-spec-system env :org.clojure/spec1)]
                              (assertions
                               "active ::impl is replaced with the stored spec-impl"
                               (::cp.spec/impl switched) => (::cp.spec/spec-impl env)))))

               (component "missing impls leave env unchanged"
                          (let [env-no-malli {::cp.spec/spec-impl  :the-spec-impl
                                              ::cp.spec/impl       :original}
                                env-no-spec  {::cp.spec/malli-impl :the-malli-impl
                                              ::cp.spec/impl       :original}]
                            (assertions
                             "returns env unchanged when :malli requested but malli-impl is missing"
                             (cp.spec/with-spec-system env-no-malli :malli) => env-no-malli
                             "returns env unchanged when default requested but spec-impl is missing"
                             (cp.spec/with-spec-system env-no-spec nil) => env-no-spec))))

;; ============================================================================
;; lookup / valid? / explain / generator / generate
;; ============================================================================

(specification "lookup"
               (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha)]
                 (assertions
                  "returns the registered spec for a known keyword"
                  (some? (cp.spec/lookup env ::test-int)) => true

                  "returns nil for an unknown keyword spec"
                  (cp.spec/lookup env ::no-such-spec) => nil)))

(specification "valid?"
               (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha)]
                 (assertions
                  "returns true when the value matches the spec"
                  (cp.spec/valid? env int? 42) => true

                  "returns false when the value does not match the spec"
                  (cp.spec/valid? env int? "not-an-int") => false

                  "works with keyword specs"
                  (cp.spec/valid? env ::test-int 42) => true
                  (cp.spec/valid? env ::test-int "x") => false)))

(specification "explain"
               (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha)]
                 (assertions
                  "returns a string for a valid value"
                  (string? (cp.spec/explain env int? 42)) => true

                  "returns a string for an invalid value"
                  (string? (cp.spec/explain env int? "bad")) => true)))

(specification "generator"
               (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha)]
                 (assertions
                  "returns a generator for a valid spec (predicate)"
                  (some? (cp.spec/generator env int?)) => true

                  "returns a generator for a valid keyword spec"
                  (some? (cp.spec/generator env ::test-int)) => true

                  "swallows exceptions and returns nil when generator creation throws"
                  (cp.spec/generator env :no-such-registered-spec) => nil)))

(specification "generate"
               (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha)]
                 (assertions
                  "returns a value matching a predicate spec"
                  (int? (cp.spec/generate env int?)) => true

                  "returns a value matching a keyword spec"
                  (int? (cp.spec/generate env ::test-int)) => true)))

;; ============================================================================
;; sample (caching wrapper)
;; ============================================================================

(specification "sample - cache miss populates the cache"
               (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha)
                     gen (cp.spec/generator env int?)]
                 (cp.spec/with-empty-cache
                   (fn []
                     (let [result (cp.spec/sample env gen)]
                       (assertions
                        "returns generated samples"
                        (every? int? result) => true

                        "produces num-samples samples (default 10)"
                        (count result) => 10

                        ;; Post-rekey (P4-8 / Task #37) the cache key is
                        ;; `(-cache-key spec)` which normalizes via `s/form`
                        ;; rather than the raw spec object. We assert that
                        ;; the call populated the cache without depending on
                        ;; the exact normalized key shape.
                        "stores the samples in the cache"
                        (boolean (seq @cp.spec/cache)) => true

                        "cached value is identical to the returned samples"
                        (identical? (first (vals @cp.spec/cache)) result) => true))))))

(specification "sample - cache hit returns cached samples"
               (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha)
                     gen (cp.spec/generator env int?)]
                 (cp.spec/with-empty-cache
                   (fn []
                     (let [first-result  (cp.spec/sample env gen)
                           second-result (cp.spec/sample env gen)]
                       (assertions
                        "the second call returns the same (identical) cached object"
                        (identical? first-result second-result) => true))))))

(specification "sample - caching disabled bypasses the cache"
               (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha {:cache-samples? false})
                     gen (cp.spec/generator env int?)]
                 (cp.spec/with-empty-cache
                   (fn []
                     (let [result (cp.spec/sample env gen)]
                       (assertions
                        "still returns generated samples"
                        (every? int? result) => true

                        "does not store anything in the cache"
                        (empty? @cp.spec/cache) => true))))))

(specification "sample - keyword spec generator"
               (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha)
                     gen (cp.spec/generator env ::test-int)]
                 (cp.spec/with-empty-cache
                   (fn []
                     (let [result (cp.spec/sample env gen)]
                       (assertions
                        "produces samples that satisfy the keyword spec"
                        (every? int? result) => true

                        ;; Post-rekey: `(-cache-key impl ::test-int)` normalizes
                        ;; via `(s/form ::test-int)` to the registered form
                        ;; (e.g. `clojure.core/int?`), so the keyword itself is
                        ;; no longer a key. Just assert the cache was populated.
                        "populates the cache for keyword-spec generators"
                        (boolean (seq @cp.spec/cache)) => true))))))

(specification "sample - predicate generator"
               (let [env (cp.spec/with-spec-impl {} :clojure.spec.alpha)
                     gen (cp.spec/generator env string?)]
                 (cp.spec/with-empty-cache
                   (fn []
                     (let [result (cp.spec/sample env gen)]
                       (assertions
                        "produces samples that satisfy the predicate"
                        (every? string? result) => true

                        ;; Post-rekey: cache key is `(-cache-key impl string?)`
                        ;; which goes through `s/form` (with safe fallback);
                        ;; assert population without coupling to the exact key.
                        "populates the cache for predicate generators"
                        (boolean (seq @cp.spec/cache)) => true))))))

;; ============================================================================
;; with-empty-cache
;; ============================================================================

(specification "with-empty-cache"
               (component "resets the cache atom to an empty map"
                          (reset! cp.spec/cache {:some-spec-key [:cached :samples]})
                          (cp.spec/with-empty-cache (fn [] nil))
                          (assertions
                           "cache is empty after the call"
                           @cp.spec/cache => {}))

               (component "passes args to the wrapped function"
                          (let [captured (atom nil)]
                            (cp.spec/with-empty-cache
                              (fn [a b c] (reset! captured [a b c]))
                              1 2 3)
                            (assertions
                             "function receives the supplied args in order"
                             @captured => [1 2 3])))

               (component "returns the value produced by the wrapped function"
                          (assertions
                           "returns f's return value"
                           (cp.spec/with-empty-cache (constantly :the-result)) => :the-result))

               (component "f sees an already-cleared cache"
                          (reset! cp.spec/cache {:pre-existing :data})
                          (let [observed (cp.spec/with-empty-cache (fn [] @cp.spec/cache))]
                            (assertions
                             "cache is empty when f runs"
                             observed => {}))))

;; ============================================================================
;; ClojureSpecAlpha protocol implementation
;; ============================================================================

(specification "ClojureSpecAlpha protocol implementation"
               (let [impl (cp.spec/->ClojureSpecAlpha {:num-samples 3 :cache-samples? true})]
                 (assertions
                  "lookup returns the spec for a registered keyword"
                  (some? (cp.spec/-lookup impl ::test-int)) => true

                  "lookup returns nil for an unknown keyword"
                  (cp.spec/-lookup impl ::no-such-spec) => nil

                  "valid? returns true for a matching value"
                  (cp.spec/-valid? impl int? 5) => true

                  "valid? returns false for a non-matching value"
                  (cp.spec/-valid? impl int? "x") => false

                  "explain returns a string"
                  (string? (cp.spec/-explain impl int? 5)) => true
                  (string? (cp.spec/-explain impl int? "x")) => true

                  "generator attaches the original spec under ::cp.spec/spec metadata"
                  (::cp.spec/spec (cp.spec/-generator impl int?)) => int?

                  "generator works for keyword specs"
                  (::cp.spec/spec (cp.spec/-generator impl ::test-int)) => ::test-int

                  "generate returns a value matching the spec"
                  (int? (cp.spec/-generate impl int?)) => true

                  "sample returns up to :num-samples values"
                  (count (cp.spec/-sample impl (s/gen int?))) => 3

                  "sample values satisfy the underlying spec"
                  (every? int? (cp.spec/-sample impl (s/gen int?))) => true)))

;; ============================================================================
;; MalliSpec protocol implementation
;; ============================================================================

(specification "MalliSpec protocol implementation"
               (let [impl (cp.spec/->MalliSpec {:num-samples 3 :cache-samples? true})]
                 (assertions
                  "lookup returns the schema value for a valid malli schema"
                  (cp.spec/-lookup impl :int) => :int

                  "lookup returns nil for an invalid malli schema"
                  (cp.spec/-lookup impl :no-such-malli-schema) => nil

                  "valid? returns true for a matching value"
                  (cp.spec/-valid? impl :int 42) => true

                  "valid? returns false for a non-matching value"
                  (cp.spec/-valid? impl :int "x") => false

                  "explain returns 'Success' for a valid value"
                  (cp.spec/-explain impl :int 42) => "Success"

                  "explain returns an explanation string for an invalid value"
                  (string? (cp.spec/-explain impl :int "x")) => true

                  "generator attaches the original schema under ::cp.spec/spec metadata"
                  (::cp.spec/spec (cp.spec/-generator impl :int)) => :int

                  "generate returns a value matching the schema"
                  (int? (cp.spec/-generate impl :int)) => true

                  "sample returns up to :num-samples values"
                  (count (cp.spec/-sample impl (cp.spec/-generator impl :int))) => 3

                  "sample values satisfy the underlying schema"
                  (every? int? (cp.spec/-sample impl (cp.spec/-generator impl :int))) => true)))
