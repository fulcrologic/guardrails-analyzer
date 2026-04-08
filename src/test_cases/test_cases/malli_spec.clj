(ns test-cases.malli-spec
  (:require
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-cases-runner :refer [deftc]]
   [com.fulcrologic.guardrails-analyzer.test-checkers :as tc]
   [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]]
   [fulcro-spec.check :as _]))

(>defn malli-identity [x] [:string => :string]              ; :binding/malli.identity
       x)

(>defn malli-bad-return [] [=> :int]                        ; :problem/malli.bad-return
       "not-an-int")

(>defn malli-nullable [s] [(? :string) => :string]          ; :binding/malli.nullable
       (or s "default"))

(deftc
  {:binding/malli.identity
   {:message  "Malli >defn binds arg with :string samples"
    :expected (_/embeds?* {::cp.art/samples (_/every?* (_/is?* string?))})}

   :problem/malli.bad-return
   {:message  "Malli >defn detects bad return type"
    :expected {::cp.art/problem-type :error/bad-return-value}}

   :binding/malli.nullable
   {:message  "Malli >defn handles (? :string) nullable spec"
    :expected (_/embeds?* {::cp.art/samples (_/is?* seq)})}})
