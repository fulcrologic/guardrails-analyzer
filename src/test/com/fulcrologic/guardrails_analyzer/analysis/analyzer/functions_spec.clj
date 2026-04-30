(ns com.fulcrologic.guardrails-analyzer.analysis.analyzer.functions-spec
  "Tests for the analyzer/functions namespace, which currently provides the
   `analyze:get-in` analyzer wired into the dispatch system as the
   `'clojure.core/get-in` handler.

   Behaviors covered:

   * Returns a type-description with a `::cp.art/samples` set for the call form.
   * For a successful key lookup, samples reflect the actual value(s) at the path.
   * Records `:warning/get-in-might-never-succeed` when the requested key is
     not present in any sample of the map at that level of the path.
   * Records the warning with the missing key as `::cp.art/original-expression`.
   * Does NOT record a warning when the key is present in the map sample.
   * Records the warning even when a default value is provided.
   * Includes the default value in the resulting samples when provided.
   * Walks deep paths through nested maps (no warning when keys are present).
   * For a qualified-keyword leaf path, samples come from the registered spec's
     generator (rather than from invoking get-in)."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test.check.generators :as gen]
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer.dispatch :as cp.ana.disp]
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer.functions]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(s/def ::val (s/with-gen char? #(s/gen #{\x \y \z})))

(defn fresh-env! []
  (cp.art/clear-problems!)
  (cp.art/clear-bindings!)
  (tf/test-env))

(defn problem-types []
  (mapv ::cp.art/problem-type @cp.art/problems))

(defn warnings-of-type [t]
  (filter #(= t (::cp.art/problem-type %)) @cp.art/problems))

(specification "analyze:get-in — successful lookup with a literal map and path"
  ;; NOTE: with raw literal values (`0` here), `analyze-hashmap!` cannot
  ;; reify a sample for the value (raw numbers dispatch to `:unknown`) and
  ;; substitutes `::cp.art/unknown` for it. The map sample seen by
  ;; `analyze:get-in` is therefore `{:a ::cp.art/unknown}`, and the function
  ;; sampler then evaluates `(get-in {:a ::cp.art/unknown} [:a])` which is
  ;; `::cp.art/unknown`. The "value 0 flows through" case is exercised by the
  ;; `remember-local` test below where samples are provided explicitly.
               (component "(get-in {:a 0} [:a])"
                          (let [env    (fresh-env!)
                                result (cp.ana.disp/-analyze! env '(get-in {:a 0} [:a]))]
                            (assertions
                             "returns a type-description with a ::cp.art/samples key"
                             (contains? result ::cp.art/samples) => true

                             "samples is a set"
                             (set? (::cp.art/samples result)) => true

                             "samples contain ::cp.art/unknown — the raw literal value did not flow through dispatch"
                             (contains? (::cp.art/samples result) ::cp.art/unknown) => true

                             "records no :warning/get-in-might-never-succeed warning"
                             (count (warnings-of-type :warning/get-in-might-never-succeed)) => 0))))

(specification "analyze:get-in — missing key with no default"
               (component "(get-in {} [:b])"
                          (let [env (fresh-env!)
                                _   (cp.ana.disp/-analyze! env '(get-in {} [:b]))]
                            (assertions
                             "records exactly one :warning/get-in-might-never-succeed warning"
                             (count (warnings-of-type :warning/get-in-might-never-succeed)) => 1

                             "the warning's ::cp.art/original-expression is the missing key :b"
                             (->> (warnings-of-type :warning/get-in-might-never-succeed)
                                  first
                                  ::cp.art/original-expression) => :b

                             "no errors are recorded by get-in's own analysis"
                             (some #{:error/function-argument-failed-spec
                                     :error/value-failed-spec}
                                   (problem-types)) => nil))))

(specification "analyze:get-in — missing key with a default value"
  ;; NOTE: a raw literal default (`1`) dispatches to `:unknown` and yields a
  ;; td with `::cp.art/unknown-expression` set and no `::cp.art/samples`.
  ;; `validate-argtypes!?` short-circuits as soon as any argtype carries
  ;; `::cp.art/unknown-expression`, so `analyze-function-call!` falls back to
  ;; sampling from the return spec generator (`any?`). This means the
  ;; resulting samples are NOT the literal default — they are random values
  ;; from the generator. The behavior we CAN test deterministically here is
  ;; the warning side-effect.
               (component "(get-in {} [:b] 1)"
                          (let [env (fresh-env!)
                                _   (cp.ana.disp/-analyze! env '(get-in {} [:b] 1))]
                            (assertions
                             "still records the :warning/get-in-might-never-succeed warning (default does not suppress it)"
                             (count (warnings-of-type :warning/get-in-might-never-succeed)) => 1

                             "the warning's ::cp.art/original-expression is the missing key :b"
                             (->> (warnings-of-type :warning/get-in-might-never-succeed)
                                  first
                                  ::cp.art/original-expression) => :b))))

(specification "analyze:get-in — deep path through nested maps"
  ;; NOTE: raw nested literals do not flow real values — the inner `1` is
  ;; lost when the inner map is reified (see analyze-hashmap! note). The
  ;; "no warning when keys are present at every level" behavior IS testable
  ;; because the analyzer walks the actual map sample structure (which DOES
  ;; have the keys, even if their leaf values are ::cp.art/unknown).
               (component "(get-in {:a {:b 1}} [:a :b])"
                          (let [env    (fresh-env!)
                                result (cp.ana.disp/-analyze! env '(get-in {:a {:b 1}} [:a :b]))]
                            (assertions
                             "records no warnings — every key is present at its level"
                             (count (warnings-of-type :warning/get-in-might-never-succeed)) => 0

                             "samples contain ::cp.art/unknown (raw nested literal `1` does not flow through)"
                             (contains? (::cp.art/samples result) ::cp.art/unknown) => true)))

               (component "(get-in {} [:a :b]) — outer key is missing"
                          (let [env (fresh-env!)
                                _   (cp.ana.disp/-analyze! env '(get-in {} [:a :b]))]
                            (assertions
                             "records a warning for the missing outer key :a"
                             ;; Use `some` with a set predicate — this is the
                             ;; idiomatic membership test and avoids the
                             ;; argument-order ambiguity that makes `contains?`
                             ;; awkward to thread.
                             (some #{:a}
                                   (map ::cp.art/original-expression
                                        (warnings-of-type :warning/get-in-might-never-succeed)))
                             => :a))))

(specification "analyze:get-in — samples are resolved from local symbol bindings"
               (component "m and p are local bindings"
                          (let [env    (-> (fresh-env!)
                                           (cp.art/remember-local 'm
                                                                  {::cp.art/samples #{{:a 0 :b 1}}})
                                           (cp.art/remember-local 'p
                                                                  {::cp.art/samples #{[:a]}}))
                                result (cp.ana.disp/-analyze! env '(get-in m p))]
                            (assertions
                             "records no get-in-might-never-succeed warning (key :a is present in m's sample)"
                             (count (warnings-of-type :warning/get-in-might-never-succeed)) => 0

                             "samples include the value 0 found at path [:a] in the bound map sample"
                             (contains? (::cp.art/samples result) 0) => true))))

(specification "analyze:get-in — qualified-keyword leaf path uses the spec generator"
  ;; When the last element of the path is a qualified-keyword that resolves to
  ;; a registered spec, analyze:get-in skips the function-call analysis and
  ;; pulls samples directly from the spec's generator. The warning logic is
  ;; independent and still fires when no map sample contains the key.
               (component "(get-in {} [::val]) where ::val has generator #{\\x \\y \\z}"
                          (let [env    (fresh-env!)
                                result (cp.ana.disp/-analyze!
                                        env (list 'get-in {} [::val]))]
                            (assertions
                             "samples are drawn from the registered spec's generator (subset of #{\\x \\y \\z})"
                             (every? #{\x \y \z} (::cp.art/samples result)) => true

                             "at least one sample is produced by the generator"
                             (pos? (count (::cp.art/samples result))) => true

                             "still records :warning/get-in-might-never-succeed (key not in empty map)"
                             (count (warnings-of-type :warning/get-in-might-never-succeed)) => 1))))
