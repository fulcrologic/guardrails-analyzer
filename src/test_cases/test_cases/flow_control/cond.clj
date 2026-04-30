(ns test-cases.flow-control.cond
  (:require
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-cases-runner :refer [deftc]]
   [com.fulcrologic.guardrails-analyzer.test-checkers :as tc]
   [com.fulcrologic.guardrails.core :refer [=> >defn]]
   [fulcro-spec.check :as _]))

(>defn n-split-union-type [n]                               ; :problem/incorrect-return-type
       [pos-int? => boolean?]
  ;; A union type has to support any number of branches, since we have things like `cond`. Technically we could
  ;; represent this as a set w/3 things in it, but knowing that only one can be the case at a time lends additional
  ;; information for later checks.
       (let [a (cond                                             ; :binding/union-type.3.branches
                 (< 8 n 11) true
                 (< 0 n 5) 42
                 :else 36.9M)]
    ;; When a given type is unequivocally required, then all versions of the union type must satisfy it
         a))

(deftc
  {:problem/incorrect-return-type
   {:message  "binding `a` is a 3-branch union (boolean?/int?/decimal?), which cannot satisfy a unequivocal boolean? return"
    :expected {::cp.art/problem-type :error/bad-return-value}}

   :binding/union-type.3.branches
   {:message  "cond with 3 branches produces samples drawn from each branch's literal type"
    :expected (_/all*
               (tc/samples-exist?*)
               (tc/samples-satisfy?* (some-fn boolean? int? decimal?)))}})
