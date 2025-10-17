(ns test-cases.macros.let-bindings-spec
  "Test cases for let binding tooltips - ensures IDE hover shows correct samples"
  (:require
    [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
    [com.fulcrologic.guardrails-analyzer.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails-analyzer.test-checkers :as tc]
    [com.fulcrologic.guardrails.core :refer [=> >defn]]
    [fulcro-spec.check :as _]))

(>defn sequential-let-bindings
  "Tests that IDE can show samples for each binding in a let chain"
  [x]
  [number? => number?]
  (let [a x                                                 ; :binding/a
        b (+ a 10)                                          ; :binding/b
        c (* b 2)]                                          ; :binding/c
    c))

(>defn let-with-conditional
  "Tests that bindings inside conditionals have path-based samples"
  [n]
  [int? => keyword?]
  (let [x n                                                 ; :binding/x-regular
        y (if (even? x)                                     ; :binding/y-conditional
            (+ x 100)
            (- x 100))]
    (if (pos? y) :positive :negative)))

(>defn let-with-nested-conditionals
  "Tests bindings in nested conditional contexts"
  [n]
  [int? => keyword?]
  (let [a n                                                 ; :binding/a-simple
        b (cond                                             ; :binding/b-cond
            (< a 5) :small                                  ; :problem/cond-1-never-else
            (< a 15) :medium                                ; :problem/cond-2-never-else
            :else :large)
        c (if (= b :small)                                  ; :binding/c-nested
            10
            20)]
    b))

(>defn mixed-let-bindings
  "Tests mix of regular and path-based bindings"
  []
  [=> int?]
  (let [a 1                                                 ; :binding/mixed-a
        b (if (pos? a) 2 3)                                 ; :binding/mixed-b :problem/pos-never-else
        c (+ a b)]                                          ; :binding/mixed-c
    c))

(deftc
  ;; Sequential let bindings - each binding should show its computed samples
  {:binding/a
   {:message  "x parameter samples propagated to a"
    :expected (_/all*
                (tc/samples-exist?*)
                (tc/samples-satisfy?* number?))}

   :binding/b
   {:message  "a + 10 samples propagated to b"
    :expected (_/all*
                (tc/samples-exist?*)
                (tc/samples-satisfy?* number?))}

   :binding/c
   {:message  "b * 2 samples propagated to c"
    :expected (_/all*
                (tc/samples-exist?*)
                (tc/samples-satisfy?* number?))}

   ;; Path-based conditional binding - should have execution paths
   :binding/x-regular
   {:message  "x has regular samples (not path-based yet)"
    :expected (_/all*
                (tc/samples-exist?*)
                (tc/samples-satisfy?* int?))}

   :binding/y-conditional
   {:message  "y should have path-based samples from both branches"
    :expected (_/all*
                (tc/samples-exist?*)
                (tc/samples-satisfy?* int?))}

   ;; Nested conditionals
   :binding/a-simple
   {:message  "a has simple int samples"
    :expected (_/all*
                (tc/samples-exist?*)
                (tc/samples-satisfy?* int?))}

   :binding/b-cond
   {:message  "b has keyword samples from cond branches"
    :expected (_/all*
                (tc/samples-exist?*)
                (tc/samples-satisfy?* (some-fn keyword? nil?)))}

   :binding/c-nested
   {:message  "c has samples from nested if"
    :expected (_/all*
                (tc/samples-exist?*)
                (tc/samples-satisfy?* #{10 20}))}

   ;; Mixed bindings
   :binding/mixed-a
   {:message  "mixed-a has literal value"
    :expected (_/all*
                (tc/samples-exist?*)
                (tc/samples-satisfy?* #{1}))}

   :binding/mixed-b
   {:message  "mixed-b has samples from conditional (path-based or regular)"
    :expected (_/all*
                (tc/samples-exist?*)
                (tc/samples-satisfy?* #{2 3}))}

   :binding/mixed-c
   {:message  "mixed-c has computed samples"
    :expected (_/all*
                (tc/samples-exist?*)
                (tc/samples-satisfy?* int?))}

   ;; Expected warnings for unreachable branches
   :problem/cond-1-never-else
   {:expected {::cp.art/problem-type :warning/if-condition-never-reaches-else-branch}}

   :problem/cond-2-never-else
   {:expected {::cp.art/problem-type :warning/if-condition-never-reaches-else-branch}}

   :problem/pos-never-else
   {:expected {::cp.art/problem-type :warning/if-condition-never-reaches-else-branch}}})
