(ns test-cases.macros.let-spec
  (:require
    [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
    [com.fulcrologic.guardrails-analyzer.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [=> >defn]]))

(>defn t [x]
  [number? => number?]
  (let [y (str "x=" 0)]                                     ; :binding/y
    x))

(deftc
  {:binding/y
   {:message "pure" :expected {::cp.art/samples #{"x=0"}}}
   })
