(ns test-cases.macros.if-spec
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [com.fulcrologic.guardrails.core :refer [=> >defn]]
    [fulcro-spec.check :as _]))

(>defn t [] [=> keyword?]
  (let [k (if true :a :b)]                                  ; :problem/always-true.literal :binding/if.keyword
    k))

(defn unk [] :unk)                                          ; :problem/unknown

(>defn t1 [] [=> any?]
  (if true                                                  ; :problem/never-else
    :a :b)
  (if false                                                 ; :problem/never-then
    :a :b)
  (if (even? 5)                                             ; :problem/never-then
    :a :b)
  (if (re-find #"is" "foo")                                 ; :problem/never-then
    :a :b)
  (if (unk)                                                 ; :problem/unknown :problem/unk-never-then :problem/unk-never-else
    :a :b))

(deftc
  {:problem/always-true.literal
   {:expected {::cp.art/problem-type :warning/if-condition-never-reaches-else-branch}}

   :binding/if.keyword
   {:expected (_/all*
                (tc/samples-exist?*)
                (tc/samples-satisfy?* keyword?)
                (tc/samples-satisfy?* #{:a :b}))}

   :problem/never-else
   {:expected {::cp.art/problem-type :warning/if-condition-never-reaches-else-branch}}

   :problem/never-then
   {:expected {::cp.art/problem-type :warning/if-condition-never-reaches-then-branch}}

   :problem/unknown
   {:expected {::cp.art/problem-type :info/failed-to-analyze-unknown-expression}}

   :problem/unk-never-then
   {:expected {::cp.art/problem-type :warning/if-condition-never-reaches-then-branch}}

   :problem/unk-never-else
   {:expected {::cp.art/problem-type :warning/if-condition-never-reaches-else-branch}}})
