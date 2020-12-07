(ns test-cases.macros.let
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]))

(>defn t [x]
  [number? => number?]
  (let [y (str "x=" 0)]; :binding/y
    x))

{
 :binding/y {:message "pure" :expected {::cp.art/samples #{"x=0"}}}
 }
