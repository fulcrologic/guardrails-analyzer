(ns com.fulcrologic.guardrails-analyzer.analysis.analyzer.literals-spec
  "Unit tests for literals analyzer (analysis/analyzer/literals.cljc).

   Distinct from src/test_cases/test-cases/literals-spec.clj which exercises
   end-to-end behavior via the test-cases-runner. This spec calls the analyzer
   functions directly to nail down individual behaviors."
  (:require
   [clojure.spec.alpha :as s]
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer.dispatch :as cp.ana.disp]
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer.literals :as sut]
   [com.fulcrologic.guardrails-analyzer.analysis.spec :as cp.spec]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

;; A registered spec used to exercise validate-samples! with a real spec
;; from the spec registry.
(s/def ::test-int int?)

(defn fresh-env! []
  (cp.art/clear-problems!)
  (cp.art/clear-bindings!)
  (tf/test-env))

(defn problem-types []
  (mapv ::cp.art/problem-type @cp.art/problems))

;; =============================================================================
;; regex?
;; =============================================================================

(specification "regex?"
               (assertions
                "returns true for a regex pattern literal"
                (sut/regex? #"foo") => true

                "returns false for a string"
                (sut/regex? "foo") => false

                "returns false for nil"
                (sut/regex? nil) => false

                "returns false for a map"
                (sut/regex? {}) => false

                "returns false for a keyword"
                (sut/regex? :foo) => false))

;; =============================================================================
;; kind->td  (lookup map from literal kind to base type description)
;; =============================================================================

(specification "kind->td"
               (assertions
                "::nil maps to spec nil? and type literal-nil"
                (::cp.art/spec (sut/kind->td ::sut/nil)) => nil?
                (::cp.art/type (sut/kind->td ::sut/nil)) => "literal-nil"

                "::char maps to spec char? and type literal-char"
                (::cp.art/spec (sut/kind->td ::sut/char)) => char?
                (::cp.art/type (sut/kind->td ::sut/char)) => "literal-char"

                "::string maps to spec string? and type literal-string"
                (::cp.art/spec (sut/kind->td ::sut/string)) => string?
                (::cp.art/type (sut/kind->td ::sut/string)) => "literal-string"

                "::regex maps to spec sut/regex? and type literal-regex"
                (::cp.art/spec (sut/kind->td ::sut/regex)) => sut/regex?
                (::cp.art/type (sut/kind->td ::sut/regex)) => "literal-regex"

                "::number maps to spec number? and type literal-number"
                (::cp.art/spec (sut/kind->td ::sut/number)) => number?
                (::cp.art/type (sut/kind->td ::sut/number)) => "literal-number"

                "::keyword maps to spec keyword? and type literal-keyword"
                (::cp.art/spec (sut/kind->td ::sut/keyword)) => keyword?
                (::cp.art/type (sut/kind->td ::sut/keyword)) => "literal-keyword"

                "::boolean has type literal-boolean and intentionally omits :spec (boolean? has no spec generator)"
                (::cp.art/type (sut/kind->td ::sut/boolean)) => "literal-boolean"
                (contains? (sut/kind->td ::sut/boolean) ::cp.art/spec) => false

                "::map maps to spec map? and type literal-map"
                (::cp.art/spec (sut/kind->td ::sut/map)) => map?
                (::cp.art/type (sut/kind->td ::sut/map)) => "literal-map"

                "::vector maps to spec vector? and type literal-vector"
                (::cp.art/spec (sut/kind->td ::sut/vector)) => vector?
                (::cp.art/type (sut/kind->td ::sut/vector)) => "literal-vector"

                "::set maps to spec set? and type literal-set"
                (::cp.art/spec (sut/kind->td ::sut/set)) => set?
                (::cp.art/type (sut/kind->td ::sut/set)) => "literal-set"

                "::quoted-symbol has type quoted-symbol and intentionally omits :spec"
                (::cp.art/type (sut/kind->td ::sut/quoted-symbol)) => "quoted-symbol"
                (contains? (sut/kind->td ::sut/quoted-symbol) ::cp.art/spec) => false

                "::quoted-expr has type quoted-expression and intentionally omits :spec"
                (::cp.art/type (sut/kind->td ::sut/quoted-expr)) => "quoted-expression"
                (contains? (sut/kind->td ::sut/quoted-expr) ::cp.art/spec) => false))

;; =============================================================================
;; literal-td
;; =============================================================================

(specification "literal-td"
               (let [env (fresh-env!)]
                 (assertions
                  "::sut/kind is set to the given kind"
                  (::sut/kind (sut/literal-td env ::sut/string "foo")) => ::sut/string

                  "::cp.art/samples is a singleton set of the value (string)"
                  (::cp.art/samples (sut/literal-td env ::sut/string "foo")) => #{"foo"}

                  "::cp.art/samples is a singleton set of the value (number)"
                  (::cp.art/samples (sut/literal-td env ::sut/number 42)) => #{42}

                  "::cp.art/samples is a singleton set of the value (keyword)"
                  (::cp.art/samples (sut/literal-td env ::sut/keyword :foo)) => #{:foo}

                  "::cp.art/original-expression defaults to the value when no orig is provided"
                  (::cp.art/original-expression (sut/literal-td env ::sut/string "foo")) => "foo"

                  "::cp.art/original-expression uses the orig argument when provided (e.g. wrapped meta map)"
                  (::cp.art/original-expression
                   (sut/literal-td env ::sut/keyword :foo {:wrapped :foo}))
                  => {:wrapped :foo}

                  "merges in spec from kind->td"
                  (::cp.art/spec (sut/literal-td env ::sut/string "foo")) => string?

                  "merges in type from kind->td"
                  (::cp.art/type (sut/literal-td env ::sut/string "foo")) => "literal-string")))

;; =============================================================================
;; coll-td
;; =============================================================================

(specification "coll-td"
               (let [env (fresh-env!)]
                 (assertions
                  "::sut/kind is set to the given kind"
                  (::sut/kind (sut/coll-td env ::sut/vector [1 2 3] #{[1 2 3]})) => ::sut/vector

                  "::cp.art/samples is the provided samples set (not wrapped further)"
                  (::cp.art/samples (sut/coll-td env ::sut/vector [1 2 3] #{[1 2 3]})) => #{[1 2 3]}

                  "::cp.art/original-expression is the source collection"
                  (::cp.art/original-expression (sut/coll-td env ::sut/vector [1 2 3] #{[1 2 3]})) => [1 2 3]

                  "merges in spec/type from kind->td for vector"
                  (::cp.art/spec (sut/coll-td env ::sut/vector [] #{[]})) => vector?
                  (::cp.art/type (sut/coll-td env ::sut/vector [] #{[]})) => "literal-vector"

                  "merges in spec/type from kind->td for map"
                  (::cp.art/spec (sut/coll-td env ::sut/map {} #{{}})) => map?
                  (::cp.art/type (sut/coll-td env ::sut/map {} #{{}})) => "literal-map"

                  "merges in spec/type from kind->td for set"
                  (::cp.art/spec (sut/coll-td env ::sut/set #{} #{#{}})) => set?
                  (::cp.art/type (sut/coll-td env ::sut/set #{} #{#{}})) => "literal-set")))

;; =============================================================================
;; validate-samples!
;; =============================================================================

(specification "validate-samples!"
               (component "when the key has no associated spec"
                          (let [env (fresh-env!)]
                            (assertions
                             "returns nil"
                             (sut/validate-samples! env :no-spec/random-key 'orig #{1 2 3}) => nil

                             "records no error"
                             (count @cp.art/problems) => 0)))

               (component "when the key has a spec and ALL samples are valid"
                          (let [env (fresh-env!)]
                            (assertions
                             "returns the (valid) samples set"
                             (sut/validate-samples! env ::test-int 'orig #{1 2 3}) => #{1 2 3}

                             "records no error"
                             (count @cp.art/problems) => 0)))

               (component "when the key has a spec and SOME sample fails"
                          (let [env    (fresh-env!)
                                result (sut/validate-samples! env ::test-int 'orig #{1 :not-int 2})]
                            (assertions
                             "returns the original samples set (caller still gets all the samples)"
                             result => #{1 :not-int 2}

                             "records exactly one :error/value-failed-spec problem"
                             (problem-types) => [:error/value-failed-spec])))

               (component "when samples is empty AND key has no spec"
                          (let [env (fresh-env!)]
                            (assertions
                             "returns nil and records no error"
                             (sut/validate-samples! env :no-spec/random-key 'orig #{}) => nil
                             (count @cp.art/problems) => 0))))

;; =============================================================================
;; cartesian-product
;; =============================================================================

(specification "cartesian-product"
               (assertions
                "single non-empty sequence yields one tuple per element"
                (set (sut/cartesian-product [1 2 3]))
                => #{'(1) '(2) '(3)}

                "two sequences yield the cross product"
                (set (sut/cartesian-product [1 2] [:a :b]))
                => #{'(1 :a) '(1 :b) '(2 :a) '(2 :b)}

                "three sequences yield the full cross product (count is product of sizes)"
                (count (sut/cartesian-product [1 2] [:a :b] [:x :y :z])) => 12

                "if any sequence is empty, returns nil (the cross product is empty)"
                (sut/cartesian-product [1 2] [])    => nil
                (sut/cartesian-product [] [:a :b])  => nil
                (sut/cartesian-product [1] [:a] []) => nil))

;; =============================================================================
;; analyze-vector!
;; =============================================================================

(specification "analyze-vector!"
  ;; NOTE: when given RAW literal elements (not wrapped via meta-wrapper from
  ;; the source reader), `analyze-vector!` dispatches each element through
  ;; `cp.ana.disp/-analyze!`. Raw numbers/strings have no defmethod for them
  ;; (they are not ifn, not seqs, not maps, etc.), so dispatch is `:unknown`,
  ;; which records `:info/failed-to-analyze-unknown-expression` and returns a
  ;; type-description with no `::cp.art/samples`. `analyze-vector-entry`
  ;; therefore conjes `::cp.art/unknown` into the sample vector for each such
  ;; element. The end-to-end tests under src/test_cases exercise the
  ;; meta-wrapped path where samples DO flow through.
               (let [env (fresh-env!)]
                 (assertions
                  "returns a single concrete vector sample of the same arity as the source — each raw literal becomes ::cp.art/unknown"
                  (::cp.art/samples (sut/analyze-vector! env [1 2 3]))
                  => #{[::cp.art/unknown ::cp.art/unknown ::cp.art/unknown]}

                  "::sut/kind is ::vector"
                  (::sut/kind (sut/analyze-vector! env [1 2 3])) => ::sut/vector

                  "::cp.art/original-expression is the source vector"
                  (::cp.art/original-expression (sut/analyze-vector! env [1 2 3])) => [1 2 3]

                  "merges spec vector? and type literal-vector"
                  (::cp.art/spec (sut/analyze-vector! env [1 2])) => vector?
                  (::cp.art/type (sut/analyze-vector! env [1 2])) => "literal-vector"

                  "empty vector produces a single empty vector sample"
                  (::cp.art/samples (sut/analyze-vector! env [])) => #{[]})))

;; =============================================================================
;; analyze-hashmap!
;; =============================================================================

(specification "analyze-hashmap!"
  ;; NOTE: keys are dispatched through `:ifn/literal` (a keyword IS an IFn) so
  ;; the keys themselves DO produce a single sample (the keyword). VALUES on
  ;; the other hand are raw literals (numbers, strings) for which there is no
  ;; samples-producing dispatch — they fall through to `:unknown`, return no
  ;; ::cp.art/samples, and `analyze-hashmap-entry` substitutes
  ;; `::cp.art/unknown` for the value while recording a
  ;; `:warning/missing-samples`. End-to-end behavior with the meta-wrapped
  ;; reader is exercised under src/test_cases.
               (let [env (fresh-env!)]
                 (assertions
                  "produces a single sample map with the literal keys, but ::cp.art/unknown for each raw value"
                  (::cp.art/samples (sut/analyze-hashmap! env {:a 1 :b 2}))
                  => #{{:a ::cp.art/unknown :b ::cp.art/unknown}}

                  "::sut/kind is ::map"
                  (::sut/kind (sut/analyze-hashmap! env {:a 1})) => ::sut/map

                  "::cp.art/original-expression is the source map"
                  (::cp.art/original-expression (sut/analyze-hashmap! env {:a 1})) => {:a 1}

                  "merges spec map? and type literal-map"
                  (::cp.art/spec (sut/analyze-hashmap! env {})) => map?
                  (::cp.art/type (sut/analyze-hashmap! env {})) => "literal-map"

                  "empty map produces a single empty-map sample"
                  (::cp.art/samples (sut/analyze-hashmap! env {})) => #{{}})))

(specification "analyze-hashmap! records :warning/qualified-keyword-missing-spec"
               (component "when a qualified keyword key has no spec in the registry"
                          (let [env (fresh-env!)]
                            (sut/analyze-hashmap! env {:no-spec/missing-key 1})
                            (assertions
                             "records the qualified-keyword-missing-spec warning"
                             (some #{:warning/qualified-keyword-missing-spec} (problem-types))
                             => :warning/qualified-keyword-missing-spec)))

               (component "when the qualified keyword has a registered spec"
                          (let [env (fresh-env!)]
                            (sut/analyze-hashmap! env {::test-int 1})
                            (assertions
                             "records NO qualified-keyword-missing-spec warning"
                             (some #{:warning/qualified-keyword-missing-spec} (problem-types))
                             => nil))))

;; =============================================================================
;; analyze-set!  (3-elem ok; 5-elem currently unbounded — REGRESSION)
;; =============================================================================

(specification "analyze-set!"
               (let [env (fresh-env!)]
                 (assertions
                  "returns a type description with the ::set kind"
                  (::sut/kind (sut/analyze-set! env #{:a :b :c})) => ::sut/set

                  "::cp.art/original-expression is the source set"
                  (::cp.art/original-expression (sut/analyze-set! env #{:a :b :c})) => #{:a :b :c}

                  "merges spec set? and type literal-set"
                  (::cp.art/spec (sut/analyze-set! env #{:a})) => set?
                  (::cp.art/type (sut/analyze-set! env #{:a})) => "literal-set"

                  "for a 3-element set of literals (each producing 1 sample) the cartesian product yields exactly one sample set"
                  (::cp.art/samples (sut/analyze-set! env #{:a :b :c})) => #{#{:a :b :c}}

                  ;; (apply cartesian-product []) is invoked with zero seqs.
                  ;; `cartesian-product` with zero seqs yields a single empty
                  ;; tuple — i.e. (()) — because `(every? seq ())` is true.
                  ;; That tuple is wrapped as a set, producing #{#{}}.
                  "an empty set yields the cartesian product of zero seqs, which is a single empty-set sample"
                  (::cp.art/samples (sut/analyze-set! env #{})) => #{#{}})))

(specification "analyze-set! 3-element bound (each element multi-sample)"
  ;; When each element produces multiple distinct samples, the size-3 case
  ;; is small enough today that the cartesian product is comfortably bounded.
  ;; This documents the currently-passing baseline and contrasts the size-5
  ;; regression below.
               (let [env  (fresh-env!)
        ;; Each input element e produces 4 disjoint samples [e 0]..[e 3].
                     stub (fn [_env e]
                            {::cp.art/samples (into #{} (for [i (range 4)] [e i]))})]
                 (with-redefs [cp.ana.disp/-analyze! stub]
                   (let [result (sut/analyze-set! env #{1 2 3})]
                     (assertions
                      "size-3 cartesian product (4^3 = 64 distinct sets) is well above 0"
                      (pos? (count (::cp.art/samples result))) => true

                      "size-3 cartesian product is comfortably bounded (≤ 100)"
                      (<= (count (::cp.art/samples result)) 100) => true)))))

(specification "analyze-set! caps cartesian product growth for size ≥ 5 (REGRESSION)"
  ;; P4 fix needed: cap analyze-set! cartesian product.
  ;;
  ;; This test is intentionally LEFT UN-SKIPPED and is expected to FAIL until
  ;; Phase 4 introduces a bound on the resulting samples set in
  ;; com.fulcrologic.guardrails-analyzer.analysis.analyzer.literals/analyze-set!.
  ;;
  ;; Today, analyze-set! does:
  ;;   (->> s (map (comp ::samples -analyze!)) (apply cartesian-product) (map set) set)
  ;; with no cap on the resulting samples count. With 5 elements, each producing
  ;; 4 disjoint samples, the cartesian product yields 4^5 = 1024 distinct
  ;; 5-element sets — far in excess of *max-samples-per-path* (20).
  ;;
  ;; After Phase 4 caps the result to *max-samples-per-path*, the assertion
  ;; below should pass.
               (let [env  (fresh-env!)
        ;; Each input element e produces 4 disjoint samples [e 0]..[e 3].
        ;; Disjoint samples ensure each (set tuple) from the cartesian product
        ;; is unique, so the final `set` does not collapse the result.
                     stub (fn [_env e]
                            {::cp.art/samples (into #{} (for [i (range 4)] [e i]))})]
                 (with-redefs [cp.ana.disp/-analyze! stub]
                   (let [result (sut/analyze-set! env #{1 2 3 4 5})]
                     (assertions
                      "samples count is bounded by *max-samples-per-path* (currently 4^5 = 1024 — fails until P4 caps it)"
                      (<= (count (::cp.art/samples result)) cp.art/*max-samples-per-path*)
                      => true)))))

(specification "analyze-set! caps cartesian product growth under real literal dispatch (no stubbing)"
  ;; Companion to the stubbed cap test above. This invokes analyze-set! on a 5-element
  ;; set using the REAL literal-dispatch path (no `with-redefs` of -analyze!), which
  ;; proves the cap is wired into the production code path, not only the stubbed
  ;; cardinality math. Real number-literal dispatch produces a single sample per
  ;; element, so the cartesian product stays well within the bound, but the assertion
  ;; uses the same *max-samples-per-path* contract regardless of element cardinality.
               (let [env    (fresh-env!)
                     result (sut/analyze-set! env #{1 2 3 4 5})]
                 (assertions
                  "samples count is bounded by *max-samples-per-path* under real dispatch"
                  (<= (count (::cp.art/samples result)) cp.art/*max-samples-per-path*)
                  => true

                  "kind is ::set even on the real-dispatch path"
                  (::sut/kind result) => ::sut/set

                  "original-expression is the source set on the real-dispatch path"
                  (::cp.art/original-expression result) => #{1 2 3 4 5})))

;; =============================================================================
;; Dispatch: :literal/wrapped (number / string / keyword / symbol-as-quoted)
;; =============================================================================

(specification "analyze-mm :literal/wrapped"
               (component "for a wrapped number literal"
                          (let [env     (fresh-env!)
                                wrapped {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                                         :kind  :number
                                         :value 42}
                                td      (cp.ana.disp/-analyze! env wrapped)]
                            (assertions
                             "samples contain only the wrapped value"
                             (::cp.art/samples td) => #{42}

                             "type is literal-number"
                             (::cp.art/type td) => "literal-number"

                             "spec is number?"
                             (::cp.art/spec td) => number?)))

               (component "for a wrapped string literal"
                          (let [env     (fresh-env!)
                                wrapped {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                                         :kind  :string
                                         :value "hello"}
                                td      (cp.ana.disp/-analyze! env wrapped)]
                            (assertions
                             "samples contain only the wrapped value"
                             (::cp.art/samples td) => #{"hello"}

                             "type is literal-string"
                             (::cp.art/type td) => "literal-string"

                             "spec is string?"
                             (::cp.art/spec td) => string?)))

               (component "for a wrapped keyword literal"
                          (let [env     (fresh-env!)
                                wrapped {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                                         :kind  :keyword
                                         :value :some/keyword}
                                td      (cp.ana.disp/-analyze! env wrapped)]
                            (assertions
                             "samples contain only the wrapped keyword value"
                             (::cp.art/samples td) => #{:some/keyword}

                             "type is literal-keyword"
                             (::cp.art/type td) => "literal-keyword"

                             "spec is keyword?"
                             (::cp.art/spec td) => keyword?

                             "records :warning/qualified-keyword-missing-spec for an unregistered qualified keyword"
                             (some #{:warning/qualified-keyword-missing-spec} (problem-types))
                             => :warning/qualified-keyword-missing-spec)))

               (component "for a wrapped qualified keyword that HAS a registered spec"
                          (let [env     (fresh-env!)
                                wrapped {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                                         :kind  :keyword
                                         :value ::test-int}
                                _       (cp.ana.disp/-analyze! env wrapped)]
                            (assertions
                             "records NO qualified-keyword-missing-spec warning"
                             (some #{:warning/qualified-keyword-missing-spec} (problem-types))
                             => nil))))

;; =============================================================================
;; Dispatch: 'clojure.core/quote — quoted-symbol vs quoted-expression
;; =============================================================================

(specification "analyze-mm 'clojure.core/quote"
               (component "for a quoted symbol literal"
                          (let [env (fresh-env!)
                                td  (cp.ana.disp/-analyze! env '(quote my-sym))]
                            (assertions
                             "samples is a singleton set of the symbol itself"
                             (::cp.art/samples td) => #{'my-sym}

                             "type is quoted-symbol (no spec is set for this kind)"
                             (::cp.art/type td) => "quoted-symbol"

                             "::sut/kind is ::quoted-symbol"
                             (::sut/kind td) => ::sut/quoted-symbol)))

               (component "for a quoted non-symbol expression (a list)"
                          (let [env (fresh-env!)
                                td  (cp.ana.disp/-analyze! env '(quote (foo bar)))]
                            (assertions
                             "samples is a singleton set of the quoted form"
                             (::cp.art/samples td) => #{'(foo bar)}

                             "type is quoted-expression"
                             (::cp.art/type td) => "quoted-expression"

                             "::sut/kind is ::quoted-expr"
                             (::sut/kind td) => ::sut/quoted-expr))))

;; =============================================================================
;; Dispatch: :literal/boolean
;; =============================================================================

(specification "analyze-mm :literal/boolean"
               (component "for the boolean literal true"
                          (let [env (fresh-env!)
                                td  (cp.ana.disp/-analyze! env true)]
                            (assertions
                             "samples is a singleton set of true"
                             (::cp.art/samples td) => #{true}

                             "type is literal-boolean"
                             (::cp.art/type td) => "literal-boolean"

                             "::sut/kind is ::boolean"
                             (::sut/kind td) => ::sut/boolean

                             "no :spec key is present (boolean? has no spec generator)"
                             (contains? td ::cp.art/spec) => false)))

               (component "for the boolean literal false"
                          (let [env (fresh-env!)
                                td  (cp.ana.disp/-analyze! env false)]
                            (assertions
                             "samples is a singleton set of false"
                             (::cp.art/samples td) => #{false}

                             "type is literal-boolean"
                             (::cp.art/type td) => "literal-boolean"))))
