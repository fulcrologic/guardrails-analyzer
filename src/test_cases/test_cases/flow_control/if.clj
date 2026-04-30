(ns test-cases.flow-control.if
  (:require
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-cases-runner :refer [deftc]]
   [com.fulcrologic.guardrails-analyzer.test-checkers :as tc]
   [com.fulcrologic.guardrails.core :refer [=> >defn]]
   [fulcro-spec.check :as _]))

(>defn provably-wrong-return-type [n]                       ; :problem/incorrect-return-type
       [pos-int? => boolean?]
       (let [a (if (< 8 n 11)                                    ; :binding/union-type
                 true
                 42)]
    ;; When a given type is unequivocally required, then all versions of the union type must satisfy it
         a))

(>defn both-branches-provable-wrong-at-same-time [n]
       [pos-int? => boolean?]
       (let [a (if (< 8 n 11)                                    ; :binding/union-type
                 true
                 42)
        ;; It is *provable* that both *sides* of the `if` are a *simultaneously* wrong for the boolean side of
        ;; the union type, therefore we give an error on both branches
             b (if (= n 9)                                       ; :binding/numeric-type
                 (+ 32 a)                                        ; :problem/arg1-could-be-boolean
                 (- a 19))]                                      ; :problem/arg0-could-be-boolean
         true))

(>defn only-one-branch-provably-wrong-at-once [n]
       [pos-int? => boolean?]
       (let [a (if (< 8 n 11)                                    ; :binding/union-type
                 true
                 42)
        ;; In this case it is *possible* for *at least one* side of the union type in `a` to work in either branch
        ;; (though only one branch works on each side of the `if`).
        ;; This is a *possible* problem, but much less likely to be a real problem. Possibly could be a "weak warning",
        ;; but since it is possible the code is perfectly fine from our limited analysis I think we should not
        ;; complain even though a casual observer can see a problem in the code.
             b (if (= n 9)                                       ; :binding/union-type
                 (+ 32 a)
                 (not a))]
         true))

(>defn undetected-unreachable-branch [n]
       [pos-int? => boolean?]
  ;; In this case it is true that the then side of this if is unreachable; however, all we have is generated samples,
  ;; which is a very limited data set to test conditions with. Therefore, the checker should find nothing wrong here.
  ;; Perhaps this is another "weak warning" case
       (if (= -1 n)
         true
         42))

(>defn provably-unreachable-branch []
       [=> boolean?]
  ;; In these cases it is trivial to prove an unreachable branch.
  ;; which is a very limited data set to test conditions with. Therefore, the checker should find nothing wrong here.
       (if true                                                  ; :problem/unreachable-else
         1
         2)
       (if false                                                 ; :problem/unreachable-then
         3
         4))

(deftc
  {:problem/incorrect-return-type
   {:message  "binding `a` is a union of boolean? and int?, which cannot satisfy a unequivocal boolean? return"
    :expected {::cp.art/problem-type :error/bad-return-value}}

   :binding/union-type
   {:message  "if branches with different literal types produce union-typed samples (boolean? OR int?)"
    :expected (_/all*
               (tc/samples-exist?*)
               (tc/samples-satisfy?* (some-fn boolean? int?)))}

   :binding/numeric-type
   {:message  "arithmetic over the (boolean? OR int?) union still yields numeric samples on each branch"
    :expected (_/all*
               (tc/samples-exist?*)
               (tc/samples-satisfy?* number?))}

   :problem/arg1-could-be-boolean
   {:message  "second arg of `+` can be a boolean drawn from the union type"
    :expected {::cp.art/problem-type :error/function-argument-failed-spec}}

   :problem/arg0-could-be-boolean
   {:message  "first arg of `-` can be a boolean drawn from the union type"
    :expected {::cp.art/problem-type :error/function-argument-failed-spec}}

   :problem/unreachable-else
   {:message  "if-condition is provably true, so else branch is unreachable"
    :expected {::cp.art/problem-type :warning/if-condition-never-reaches-else-branch}}

   :problem/unreachable-then
   {:message  "if-condition is provably false, so then branch is unreachable"
    :expected {::cp.art/problem-type :warning/if-condition-never-reaches-then-branch}}})
