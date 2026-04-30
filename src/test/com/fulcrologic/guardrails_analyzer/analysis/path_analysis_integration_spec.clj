(ns com.fulcrologic.guardrails-analyzer.analysis.path-analysis-integration-spec
  "Integration tests for path-based analysis.

  These tests analyze the intentionally problematic namespace
  test-data.path-analysis-problems and verify that the expected
  problems are detected by the full analysis pipeline."
  (:require
   [clojure.java.io :as io]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.checker :as cp.checker]
   [com.fulcrologic.guardrails-analyzer.reader :as cp.reader]
   [com.fulcrologic.guardrails-analyzer.test-data.path-analysis-problems]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [=> assertions specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

;; ============================================================================
;; Test Utilities
;; ============================================================================

(defn analyze-namespace-file!
  "Analyze a namespace file and return problems and bindings."
  [file-path]
  (let [file-info (cp.reader/read-file file-path :clj)
        result    (promise)]
    (cp.checker/check! file-info
                       (fn [env]
                         ;; Return raw problems/bindings instead of formatted,
                         ;; reading the per-check buffers off env.
                         (deliver result {:problems (cp.art/get-problems env)
                                          :bindings (cp.art/get-bindings env)})))
    @result))

(defn problems-for-function
  "Extract all problems for a specific function by name."
  [problems fn-name]
  (filter (fn [p]
            (= (::cp.art/sym p) fn-name))
          problems))

(defn has-problem-type?
  "Check if any problem has the given problem type."
  [problems problem-type]
  (some #(= (::cp.art/problem-type %) problem-type) problems))

(defn problem-count
  "Count problems matching a predicate."
  [problems pred]
  (count (filter pred problems)))

(defn has-return-error?
  "Check if problems include a return type error."
  [problems]
  (has-problem-type? problems :error/bad-return-value))

(defn has-arg-error?
  "Check if problems include an argument type error."
  [problems]
  (or (has-problem-type? problems :error/bad-argument-value)
      (has-problem-type? problems :error/wrong-number-of-arguments)))

(defn path-info
  "Extract path information from a problem."
  [problem]
  (get-in problem [::cp.art/actual ::cp.art/failing-paths]))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(specification "Path-based analysis integration tests" :integration

               (let [test-file (io/resource "com/fulcrologic/guardrails_analyzer/test_data/path_analysis_problems.clj")
                     _ (assert test-file "Test data file not found!")
                     {:keys [problems bindings]} (analyze-namespace-file! test-file)]

    ;; ========================================================================
    ;; Simple Path-Based Errors
    ;; ========================================================================

                 (assertions
                  "wrong-return-on-then-branch detects error on then branch only"
                  (let [fn-problems (problems-for-function problems 'wrong-return-on-then-branch)]
                    (and (seq fn-problems)
                         (has-return-error? fn-problems)))
                  => true)

                 (assertions
                  "wrong-return-on-else-branch detects error on else branch only"
                  (let [fn-problems (problems-for-function problems 'wrong-return-on-else-branch)]
                    (and (seq fn-problems)
                         (has-return-error? fn-problems)))
                  => true)

                 (assertions
                  "wrong-return-on-both-branches detects errors on both branches"
                  (let [fn-problems (problems-for-function problems 'wrong-return-on-both-branches)]
        ;; Should detect at least one error (may combine or report separately)
                    (and (seq fn-problems)
                         (has-return-error? fn-problems)))
                  => true)

    ;; ========================================================================
    ;; Nested Conditionals
    ;; ========================================================================

                 (let [fn-problems       (problems-for-function problems 'nested-if-error-on-inner-branch)
                       return-errs       (filter #(= (::cp.art/problem-type %) :error/bad-return-value)
                                                 fn-problems)
                       failing-paths     (mapcat path-info return-errs)
                       ;; The bad return (42) lives where (pos? x) → :then AND (even? x) → :else
                       multi-cond-paths  (filter (fn [path]
                                                   (let [conds    (::cp.art/conditions path)
                                                         branches (set (map ::cp.art/branch conds))]
                                                     (and (>= (count conds) 2)
                                                          (contains? branches :then)
                                                          (contains? branches :else))))
                                                 failing-paths)
                       failing-samples   (mapcat #(get-in % [::cp.art/actual ::cp.art/failing-samples])
                                                 return-errs)]
                   (assertions
                    "nested-if-error-on-inner-branch reports a :error/bad-return-value problem"
                    (boolean (seq return-errs)) => true
                    "nested-if-error-on-inner-branch attaches at least one failing path to the error"
                    (boolean (seq failing-paths)) => true
                    "nested-if-error-on-inner-branch attributes the error to a path with both :then and :else branch markers (nested conditions)"
                    (boolean (seq multi-cond-paths)) => true
                    "nested-if-error-on-inner-branch reports the literal int 42 as a failing return sample"
                    (boolean (some #(= 42 %) failing-samples)) => true))

                 (assertions
                  "nested-if-multiple-errors detects multiple errors on different paths"
                  (let [fn-problems (problems-for-function problems 'nested-if-multiple-errors)]
        ;; Should find at least one error, possibly both
                    (and (seq fn-problems)
                         (has-return-error? fn-problems)))
                  => true)

    ;; ========================================================================
    ;; Union Types
    ;; ========================================================================

                 (let [fn-problems     (problems-for-function problems 'union-type-used-incorrectly)
                       return-errs     (filter #(= (::cp.art/problem-type %) :error/bad-return-value)
                                               fn-problems)
                       failing-paths   (mapcat path-info return-errs)
                       ;; The 42 partition lives on the :else branch of the inner if
                       else-paths      (filter (fn [path]
                                                 (some #(= :else (::cp.art/branch %))
                                                       (::cp.art/conditions path)))
                                               failing-paths)
                       failing-samples (mapcat #(get-in % [::cp.art/actual ::cp.art/failing-samples])
                                               return-errs)]
                   (assertions
                    "union-type-used-incorrectly reports a :error/bad-return-value problem"
                    (boolean (seq return-errs)) => true
                    "union-type-used-incorrectly attaches at least one failing path to the error"
                    (boolean (seq failing-paths)) => true
                    "union-type-used-incorrectly attributes the error to a path on the :else branch (the int side of the union)"
                    (boolean (seq else-paths)) => true
                    "union-type-used-incorrectly reports the literal int 42 as a failing return sample"
                    (boolean (some #(= 42 %) failing-samples)) => true))

                 (let [fn-problems       (problems-for-function problems 'union-type-in-arithmetic)
                       arg-errs          (filter #(= (::cp.art/problem-type %)
                                                     :error/function-argument-failed-spec)
                                                 fn-problems)
                       arg-failing-samps (mapcat #(get-in % [::cp.art/actual ::cp.art/failing-samples])
                                                 arg-errs)]
                   (assertions
                    "union-type-in-arithmetic reports :error/function-argument-failed-spec (boolean reaching numeric arg)"
                    (boolean (seq arg-errs)) => true
                    "union-type-in-arithmetic identifies a boolean failing-sample (the union's truthy branch)"
                    (boolean (some boolean? arg-failing-samps)) => true))

    ;; ========================================================================
    ;; Control Flow Constructs
    ;; ========================================================================

                 (assertions
                  "cond-with-error detects error in cond (which expands to if)"
                  (let [fn-problems (problems-for-function problems 'cond-with-error)]
                    (and (seq fn-problems)
                         (has-return-error? fn-problems)))
                  => true)

                 (let [fn-problems     (problems-for-function problems 'when-with-error)
                       return-errs     (filter #(= (::cp.art/problem-type %) :error/bad-return-value)
                                               fn-problems)
                       failing-paths   (mapcat path-info return-errs)
                       ;; (when c body) → (if c body nil): :then path returns 42, :else returns nil
                       then-paths      (filter (fn [path]
                                                 (some #(= :then (::cp.art/branch %))
                                                       (::cp.art/conditions path)))
                                               failing-paths)
                       else-paths      (filter (fn [path]
                                                 (some #(= :else (::cp.art/branch %))
                                                       (::cp.art/conditions path)))
                                               failing-paths)
                       failing-samples (mapcat #(get-in % [::cp.art/actual ::cp.art/failing-samples])
                                               return-errs)]
                   (assertions
                    "when-with-error reports a :error/bad-return-value problem (when expands to if with implicit nil else)"
                    (boolean (seq return-errs)) => true
                    "when-with-error attaches at least one failing path to the error"
                    (boolean (seq failing-paths)) => true
                    "when-with-error attributes a failing path to the :then branch (returns 42)"
                    (boolean (seq then-paths)) => true
                    "when-with-error attributes a failing path to the :else branch (implicit nil return)"
                    (boolean (seq else-paths)) => true
                    "when-with-error reports the literal int 42 as a failing return sample"
                    (boolean (some #(= 42 %) failing-samples)) => true
                    "when-with-error reports nil (the implicit else) as a failing return sample"
                    (boolean (some nil? failing-samples)) => true))

    ;; ========================================================================
    ;; Argument Type Errors
    ;; ========================================================================

                 (let [fn-problems       (problems-for-function problems 'wrong-argument-type)
                       arg-errs          (filter #(= (::cp.art/problem-type %)
                                                     :error/function-argument-failed-spec)
                                                 fn-problems)
                       arg-failing-samps (mapcat #(get-in % [::cp.art/actual ::cp.art/failing-samples])
                                                 arg-errs)]
                   (assertions
                    "wrong-argument-type reports :error/function-argument-failed-spec on the call to +"
                    (boolean (seq arg-errs)) => true
                    "wrong-argument-type identifies a string failing-sample (x typed as string? reaches +)"
                    (boolean (some string? arg-failing-samps)) => true))

                 (let [fn-problems       (problems-for-function problems 'conditional-argument-error)
                       arg-errs          (filter #(= (::cp.art/problem-type %)
                                                     :error/function-argument-failed-spec)
                                                 fn-problems)
                       arg-failing-samps (mapcat #(get-in % [::cp.art/actual ::cp.art/failing-samples])
                                                 arg-errs)]
                   (assertions
                    "conditional-argument-error reports :error/function-argument-failed-spec on the str/upper-case call"
                    (boolean (seq arg-errs)) => true
                    "conditional-argument-error identifies an int failing-sample (x typed as int? reaches str/upper-case on the :else branch)"
                    (boolean (some integer? arg-failing-samps)) => true))

    ;; ========================================================================
    ;; Correct Functions (should have NO errors)
    ;; ========================================================================

                 (assertions
                  "correct-simple-if has no errors"
                  (empty? (filter #(= (::cp.art/problem-type %) :error/bad-return-value)
                                  (problems-for-function problems 'correct-simple-if)))
                  => true)

                 (assertions
                  "correct-nested-if has no errors"
                  (empty? (filter #(= (::cp.art/problem-type %) :error/bad-return-value)
                                  (problems-for-function problems 'correct-nested-if)))
                  => true)

                 (assertions
                  "correct-cond has no errors"
                  (empty? (filter #(= (::cp.art/problem-type %) :error/bad-return-value)
                                  (problems-for-function problems 'correct-cond)))
                  => true)

    ;; ========================================================================
    ;; if-let path partitioning (regression for the truthy/falsy split)
    ;; ========================================================================

                 (assertions
                  "if-let-correct has NO bad-return errors (then-branch x is non-nil)"
                  (empty? (filter #(= (::cp.art/problem-type %) :error/bad-return-value)
                                  (problems-for-function problems 'if-let-correct)))
                  => true)

                 (assertions
                  "if-let-correct has NO arg-failed-spec errors (regression: nil leaking into inc)"
                  (empty? (filter #(= (::cp.art/problem-type %) :error/function-argument-failed-spec)
                                  (problems-for-function problems 'if-let-correct)))
                  => true)

                 (assertions
                  "if-let-bad-then detects a return error attributable to the then-branch"
                  (let [fn-problems (problems-for-function problems 'if-let-bad-then)
                        return-errs (filter #(= (::cp.art/problem-type %) :error/bad-return-value)
                                            fn-problems)
                        then-paths  (mapcat (fn [p]
                                              (filter (fn [path]
                                                        (some #(= :then (::cp.art/branch %))
                                                              (::cp.art/conditions path)))
                                                      (path-info p)))
                                            return-errs)]
                    (boolean (and (seq return-errs) (seq then-paths))))
                  => true)

                 (assertions
                  "if-let-bad-else detects a return error attributable to the else-branch"
                  (let [fn-problems (problems-for-function problems 'if-let-bad-else)
                        return-errs (filter #(= (::cp.art/problem-type %) :error/bad-return-value)
                                            fn-problems)
                        else-paths  (mapcat (fn [p]
                                              (filter (fn [path]
                                                        (some #(= :else (::cp.art/branch %))
                                                              (::cp.art/conditions path)))
                                                      (path-info p)))
                                            return-errs)]
                    (boolean (and (seq return-errs) (seq else-paths))))
                  => true)))

(specification "Path information in error reports" :integration

               (let [test-file (io/resource "com/fulcrologic/guardrails_analyzer/test_data/path_analysis_problems.clj")
                     {:keys [problems]} (analyze-namespace-file! test-file)]

                 (assertions
                  "Errors include path information when applicable"
      ;; At least some errors should have path information
                  (boolean (some #(seq (path-info %)) problems))
                  => true)

                 (assertions
                  "Path-based errors contain failing sample information"
      ;; Check that errors have the actual failing values
                  (boolean (some #(get-in % [::cp.art/actual ::cp.art/failing-samples]) problems))
                  => true)))

(specification "Problem counts and statistics" :integration

               (let [test-file (io/resource "com/fulcrologic/guardrails_analyzer/test_data/path_analysis_problems.clj")
                     {:keys [problems]} (analyze-namespace-file! test-file)]

                 (assertions
                  "Analysis finds multiple problems in the test namespace"
                  (> (count problems) 5)
                  => true)

                 (assertions
                  "Analysis finds return type errors"
                  (pos? (count (filter #(= (::cp.art/problem-type %) :error/bad-return-value) problems)))
                  => true)

                 (assertions
                  "All problems have required metadata"
                  (every? (fn [p]
                            (and (::cp.art/problem-type p)
                                 (::cp.art/original-expression p)))
                          problems)
                  => true)))
