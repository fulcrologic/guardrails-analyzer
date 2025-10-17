(ns com.fulcrologic.guardrails-analyzer.test-data.path-analysis-problems
  "Intentionally problematic code for integration testing.

  This namespace contains various Guardrails functions with known type errors,
  particularly focusing on path-based analysis scenarios. Integration tests
  will analyze this namespace and verify the expected problems are detected."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.guardrails.core :refer [=> >defn]]))

;; ============================================================================
;; Simple Path-Based Errors
;; ============================================================================

(>defn wrong-return-on-then-branch
  "Returns wrong type on the then branch only."
  [x]
  [int? => string?]
  (if (even? x)
    42                                                      ; ERROR: should return string, returns int
    "odd"))

(>defn wrong-return-on-else-branch
  "Returns wrong type on the else branch only."
  [x]
  [int? => string?]
  (if (even? x)
    "even"
    99))                                                    ; ERROR: should return string, returns int

(>defn wrong-return-on-both-branches
  "Returns wrong type on both branches."
  [x]
  [int? => string?]
  (if (even? x)
    42                                                      ; ERROR: should return string, returns int
    99))                                                    ; ERROR: should return string, returns int

;; ============================================================================
;; Nested Conditionals
;; ============================================================================

(>defn nested-if-error-on-inner-branch
  "Nested if with error in one of the inner branches."
  [x]
  [int? => string?]
  (if (pos? x)
    (if (even? x)
      "pos-even"
      42)                                                   ; ERROR: pos? true, even? false path returns int
    "non-positive"))

(>defn nested-if-multiple-errors
  "Nested if with errors on multiple paths."
  [x]
  [int? => string?]
  (if (pos? x)
    (if (even? x)
      "pos-even"
      42)                                                   ; ERROR: returns int on pos-odd path
    99))                                                    ; ERROR: returns int on non-positive path

;; ============================================================================
;; Union Types from Branches
;; ============================================================================

(>defn union-type-used-incorrectly
  "Creates union type from if, then uses it incorrectly."
  [n]
  [pos-int? => boolean?]
  (let [a (if (< 8 n 11)
            true
            42)]
    ;; 'a' is now either boolean or int (union type)
    ;; Using it where boolean is required should fail
    a))                                                     ; ERROR: could be int

(>defn union-type-in-arithmetic
  "Creates union type, uses in arithmetic where both branches fail differently."
  [n]
  [pos-int? => boolean?]
  (let [a (if (< 8 n 11)
            true
            42)]
    ;; Both branches of this if are problematic for different reasons
    (if (= n 9)
      (+ 32 a)                                              ; ERROR: arg could be boolean
      (- a 19))))                                           ; ERROR: arg could be boolean

;; ============================================================================
;; Control Flow Constructs (cond, when, etc.)
;; ============================================================================

(>defn cond-with-error
  "Uses cond (which expands to nested ifs) with type error on one branch."
  [x]
  [int? => string?]
  (cond
    (< x 0) "negative"
    (= x 0) "zero"
    (< x 10) "small"
    :else 999))                                             ; ERROR: should return string, returns int

(>defn when-with-error
  "Uses when with type error."
  [x]
  [int? => string?]
  (when (pos? x)
    42))                                                    ; ERROR: returns int or nil, expects string

;; ============================================================================
;; Argument Type Errors
;; ============================================================================

(>defn wrong-argument-type
  "Passes wrong type to function expecting specific type."
  [x]
  [string? => int?]
  (+ x 10))                                                 ; ERROR: + expects number, x is string

(>defn conditional-argument-error
  "Argument error that only occurs on specific path."
  [x]
  [int? => int?]
  (if (even? x)
    (+ x 10)
    (str/upper-case x)))                                    ; ERROR: str/upper-case expects string, x is int

;; ============================================================================
;; Correct Functions (no errors)
;; ============================================================================

(>defn correct-simple-if
  "This function should have NO errors."
  [x]
  [int? => string?]
  (if (even? x)
    "even"
    "odd"))

(>defn correct-nested-if
  "Correctly typed nested conditionals."
  [x]
  [int? => keyword?]
  (if (pos? x)
    (if (even? x)
      :pos-even
      :pos-odd)
    :non-positive))

(>defn correct-cond
  "Correctly typed cond."
  [x]
  [int? => string?]
  (cond
    (< x 0) "negative"
    (= x 0) "zero"
    (< x 10) "small"
    :else "large"))
