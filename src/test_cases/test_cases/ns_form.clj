(ns test-cases.ns-form
  (:require
    [clojure.string :refer [join]]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [com.fulcrologic.copilot.artifacts :as cp.art]))

(inc 0)

(let [x (inc 0)] ; :binding/top-level-let-works
  (>defn t0 [] [=> keyword?] x)) ; :problem/top-level-let-works

(join "," [:a :b])

(>defn t [] [=> nil?]
  (join "," [1 2])
  nil)

(deftc
  {:binding/top-level-let-works
   {:expected {::cp.art/samples #{1}}}

   :problem/top-level-let-works
   {:expected {::cp.art/actual {::cp.art/failing-samples #{1}}}}})
