(ns test-cases.macros.and-and-or
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [fulcro-spec.check :as _]))

(>defn or-type [n m o]
  [(? int?) (? boolean?) (? string?) => boolean?]
  ;; Union type int? OR boolean? OR string? OR nil?
  ;; I think we have to remove the nil/false values from generated sets to get the true union, but then have
  ;; an extra nil/false set
  ;; 1. Generate n, m, and o
  ;; 2. Remove nil/false from samples in (1)
  ;; 3. Union is #{nil} OR #{false} OR n OR m OR o
  ;; Checking union types varies by case:
  ;; If the code is unequivocally reachable, then you just use the union as a sample set, and all must pass
  ;; But if the code has data dependencies, then you cannot be sure that the logic is actually wrong.
  (let [a (or n m o)]                                       ; :binding/or
    a)
  true)

(>defn and-type [n m o]
  [(? int?) (? boolean?) (? string?) => boolean?]
  ;; and will return nil, or false (never true), or string: union type of #{nil} OR #{false} OR string?
  (let [a (and n m o)]                                      ; :binding/and
    ;; When a given type is unequivocally required, then all versions of the union type must satisfy it
    a)
  true)
