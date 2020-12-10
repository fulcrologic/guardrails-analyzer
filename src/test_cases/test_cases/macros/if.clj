(ns test-cases.macros.if
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]))

(>defn t [] [=> keyword?]
  (let [k (if true :a :b)] k)) ; :problem/always-true.literal :binding/if.keyword

(deftc
  {:problem/always-true.literal
   {:expected {::cp.art/problem-type :warning/if-condition-never-reaches-else-branch}}

   :binding/if.keyword
   {:expected {::cp.art/samples #{:a :b}}}
   })
