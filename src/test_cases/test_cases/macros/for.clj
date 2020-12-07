(ns test-cases.macros.for
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]))

(>defn t [] [=> coll?]
  (for [x :kw] x) ; :problem/not-a-coll
  )

{
 :problem/not-a-coll {:message "t1" :expected {::cp.art/problem-type :error/expected-seqable-collection}}
 }
