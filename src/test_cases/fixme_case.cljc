(ns fixme-case
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]))

(>defn f [x] [string? => int?] (inc x) "str") ; assert: a, b

{
 :a {:message "lorem" :expected {::cp.art/problem-type :error/bad-return-value}}
 :b {:message "ipsum" :expected {::cp.art/problem-type :error/function-argument-failed-spec}}
 }
