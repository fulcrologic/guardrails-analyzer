(ns macros.if
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]))

(>defn t [] [=> keyword?]
  (if true :a :b)) ; assert: t1

{:t1 {:message "t1" :expected {::cp.art/problem-type :warning/if-condition-never-reaches-else-branch}}}
