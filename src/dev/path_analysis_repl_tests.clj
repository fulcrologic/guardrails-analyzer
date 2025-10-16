(ns path-analysis-repl-tests
  "REPL tests for path-based analysis of simple if conditionals.

  These tests verify that the current implementation correctly:
  - Partitions samples based on pure predicates
  - Tracks execution paths with conditions
  - Handles nested ifs
  - Reports errors with path information

  To run: Load this file in the REPL and call (run-all-tests)"
  (:require
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.copilot.analysis.analyzer :as cp.ana]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.guardrails.core :refer [=> >defn]]))

;; ============================================================================
;; Test Utilities
;; ============================================================================

(defn analyze-expr
  "Analyze an expression and return the type description."
  [env expr]
  (cp.ana/analyze! env expr))

(defn get-paths
  "Extract execution paths from a type description."
  [td]
  (::cp.art/execution-paths td))

(defn get-samples
  "Extract all samples from a type description."
  [td]
  (cp.art/extract-all-samples td))

(defn print-paths
  "Pretty print execution paths for inspection."
  [td]
  (println "\n=== Execution Paths ===")
  (doseq [path (get-paths td)]
    (println "\nPath" (::cp.art/path-id path))
    (println "  Samples:" (::cp.art/samples path))
    (println "  Bindings:" (::cp.art/path-bindings path))
    (println "  Conditions:")
    (doseq [cond (::cp.art/conditions path)]
      (println "    -" (select-keys cond [::cp.art/condition-expression
                                          ::cp.art/determined?
                                          ::cp.art/branch
                                          ::cp.art/condition-value]))))
  (println))

(defn check-form
  "Check a form and return problems."
  [form]
  (cp.art/clear-problems! nil)
  (let [env (cp.art/build-env {:current-ns 'test-ns})]
    (cp.ana/analyze! env form)
    @cp.art/problems))

;; ============================================================================
;; Test Case 1: Simple If with even? predicate
;; ============================================================================

(comment
  ;; Test: Simple if with even? predicate
  ;; Expected: Samples should be partitioned into even/odd sets

  (def test-env-1
    (-> (cp.art/build-env {:current-ns 'test-ns})
      ;; Add some sample integers for x
      (cp.art/remember-local 'x {::cp.art/samples #{1 2 3 4 5 6 7 8 9 10}})))

  (def result-1
    (analyze-expr test-env-1
      '(if (even? x)
         (+ x 100)
         (- x 100))))

  (print-paths result-1)

  ;; Expected output:
  ;; - Path 0 (then): samples should be {102 104 106 108 110} (even x + 100)
  ;;   - Condition: (even? x), determined? true, branch :then, value true
  ;;   - Bindings: {x #{2 4 6 8 10}}
  ;; - Path 1 (else): samples should be {-99 -97 -95 -93 -91} (odd x - 100)
  ;;   - Condition: (even? x), determined? true, branch :else, value false
  ;;   - Bindings: {x #{1 3 5 7 9}}
  )

;; ============================================================================
;; Test Case 2: Simple If with pos? predicate
;; ============================================================================

(comment
  ;; Test: Simple if with pos? predicate on mixed positive/negative samples

  (def test-env-2
    (-> (cp.art/build-env {:current-ns 'test-ns})
      (cp.art/remember-local 'x {::cp.art/samples #{-5 -3 -1 0 1 3 5}})))

  (def result-2
    (analyze-expr test-env-2
      '(if (pos? x)
         :positive
         :negative)))

  (print-paths result-2)

  ;; Expected output:
  ;; - Path 0 (then): samples {:positive}
  ;;   - Bindings: {x #{1 3 5}}
  ;; - Path 1 (else): samples {:negative}
  ;;   - Bindings: {x #{-5 -3 -1 0}}
  )

;; ============================================================================
;; Test Case 3: Nested Ifs with simple predicates
;; ============================================================================

(comment
  ;; Test: Nested ifs should create multiple paths

  (def test-env-3
    (-> (cp.art/build-env {:current-ns 'test-ns})
      (cp.art/remember-local 'x {::cp.art/samples #{-2 -1 0 1 2}})))

  (def result-3
    (analyze-expr test-env-3
      '(if (pos? x)
         (if (even? x)
           :pos-even
           :pos-odd)
         :non-pos)))

  (print-paths result-3)

  ;; Expected output:
  ;; - Path 0: samples {:pos-even}
  ;;   - Conditions: [(pos? x) true, (even? x) true]
  ;;   - Bindings should show x filtered to {2}
  ;; - Path 1: samples {:pos-odd}
  ;;   - Conditions: [(pos? x) true, (even? x) false]
  ;;   - Bindings should show x filtered to {1}
  ;; - Path 2: samples {:non-pos}
  ;;   - Conditions: [(pos? x) false]
  ;;   - Bindings should show x as {-2 -1 0}
  )

;; ============================================================================
;; Test Case 4: If with non-pure condition (should use superposition)
;; ============================================================================

(comment
  ;; Test: If with unknown/impure predicate should NOT partition samples

  (def test-env-4
    (-> (cp.art/build-env {:current-ns 'test-ns})
      (cp.art/remember-local 'x {::cp.art/samples #{1 2 3 4 5}})))

  (def result-4
    (analyze-expr test-env-4
      '(if (unknown-predicate? x)                           ; Not marked as pure
         :then-value
         :else-value)))

  (print-paths result-4)

  ;; Expected output (superposition):
  ;; - Path 0 (then): samples {:then-value}
  ;;   - Conditions: determined? false
  ;;   - Bindings: {x #{1 2 3 4 5}} (all original samples)
  ;; - Path 1 (else): samples {:else-value}
  ;;   - Conditions: determined? false
  ;;   - Bindings: {x #{1 2 3 4 5}} (all original samples)
  )

;; ============================================================================
;; Test Case 5: Error reporting with path information
;; ============================================================================

(comment
  ;; Test: Error should include path information when spec violation occurs

  (def test-form-5
    '(>defn test-fn [x]
       [int? => string?]                                    ; Expects string return
       (if (even? x)
         42                                                 ; Wrong! Returns int on even path
         "odd")))                                           ; Correct on odd path

  (def problems-5 (check-form test-form-5))

  (println "\n=== Problems ===")
  (pprint problems-5)

  ;; Expected: Error should indicate:
  ;; - Problem on the "then" branch (even? x is true)
  ;; - Failing samples: 42
  ;; - Path condition: (even? x) was truthy
  )

;; ============================================================================
;; Test Case 6: Both branches fail (should report both)
;; ============================================================================

(comment
  ;; Test: When both branches violate spec, both should be reported

  (def test-form-6
    '(>defn test-fn [x]
       [int? => string?]
       (if (even? x)
         42                                                 ; Wrong!
         99)))                                              ; Also wrong!

  (def problems-6 (check-form test-form-6))

  (println "\n=== Problems ===")
  (pprint problems-6)

  ;; Expected: Errors on both paths
  )

;; ============================================================================
;; Test Case 7: Verify sample partitioning correctness
;; ============================================================================

(comment
  ;; Direct test of partition-samples-by-condition

  (def test-env-7 (cp.art/build-env {:current-ns 'test-ns}))

  (def partition-result
    (cp.art/partition-samples-by-condition
      test-env-7
      '(even? x)
      'x
      #{1 2 3 4 5 6 7 8 9 10}))

  (println "\n=== Partition Result ===")
  (pprint partition-result)

  ;; Expected:
  ;; {:true-samples #{2 4 6 8 10}
  ;;  :false-samples #{1 3 5 7 9}
  ;;  :undetermined-samples #{}
  ;;  :determined? true}
  )

;; ============================================================================
;; Test Case 8: Let binding with if maintains path information
;; ============================================================================

(comment
  ;; Test: Let bindings should preserve path information

  (def test-env-8
    (-> (cp.art/build-env {:current-ns 'test-ns})
      (cp.art/remember-local 'x {::cp.art/samples #{1 2 3 4 5}})))

  (def result-8
    (analyze-expr test-env-8
      '(let [y (if (even? x) 100 200)]
         (+ y x))))

  (print-paths result-8)

  ;; Expected:
  ;; - Path 0: samples {102 104 106} (even x: 100 + 2, 100 + 4, 100 + 6)
  ;;   - y bound to 100, x bound to {2 4 6}... wait, that's not quite right
  ;;   - Actually: 100 + 2 = 102, 100 + 4 = 104, 100 + 6... hmm
  ;;   - Let me think: x in {2, 4, 6} (even), y = 100, so y + x = {102, 104, 106}
  ;; - Path 1: samples {201 203 205} (odd x: 200 + 1, 200 + 3, 200 + 5)
  )

;; ============================================================================
;; Run All Tests
;; ============================================================================

(defn run-all-tests
  "Run all REPL tests and print results."
  []
  (println "\n" (apply str (repeat 80 "=")))
  (println "Running Path Analysis REPL Tests")
  (println (apply str (repeat 80 "=")))

  (println "\n[Test 1] Simple if with even? predicate")
  (println "=" (apply str (repeat 78 "-")))
  (eval '(do
           (def test-env-1
             (-> (cp.art/build-env {:current-ns 'test-ns})
               (cp.art/remember-local 'x {::cp.art/samples #{1 2 3 4 5 6 7 8 9 10}})))
           (def result-1
             (analyze-expr test-env-1
               '(if (even? x)
                  (+ x 100)
                  (- x 100))))
           (print-paths result-1)))

  (println "\n[Test 2] Simple if with pos? predicate")
  (println "=" (apply str (repeat 78 "-")))
  (eval '(do
           (def test-env-2
             (-> (cp.art/build-env {:current-ns 'test-ns})
               (cp.art/remember-local 'x {::cp.art/samples #{-5 -3 -1 0 1 3 5}})))
           (def result-2
             (analyze-expr test-env-2
               '(if (pos? x)
                  :positive
                  :negative)))
           (print-paths result-2)))

  (println "\n[Test 3] Nested ifs with simple predicates")
  (println "=" (apply str (repeat 78 "-")))
  (eval '(do
           (def test-env-3
             (-> (cp.art/build-env {:current-ns 'test-ns})
               (cp.art/remember-local 'x {::cp.art/samples #{-2 -1 0 1 2}})))
           (def result-3
             (analyze-expr test-env-3
               '(if (pos? x)
                  (if (even? x)
                    :pos-even
                    :pos-odd)
                  :non-pos)))
           (print-paths result-3)))

  (println "\n[Test 7] Direct partition-samples-by-condition test")
  (println "=" (apply str (repeat 78 "-")))
  (eval '(do
           (def test-env-7 (cp.art/build-env {:current-ns 'test-ns}))
           (def partition-result
             (cp.art/partition-samples-by-condition
               test-env-7
               '(even? x)
               'x
               #{1 2 3 4 5 6 7 8 9 10}))
           (println "\nPartition Result:")
           (pprint partition-result)))

  (println "\n" (apply str (repeat 80 "=")))
  (println "Tests Complete!")
  (println (apply str (repeat 80 "=")))
  (println "\nManual tests to run in REPL:")
  (println "- Test 4: Non-pure condition (superposition)")
  (println "- Test 5: Error reporting with path info")
  (println "- Test 6: Both branches fail")
  (println "- Test 8: Let binding with if"))
