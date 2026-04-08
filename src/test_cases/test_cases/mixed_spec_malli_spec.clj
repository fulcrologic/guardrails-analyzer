(ns test-cases.mixed-spec-malli-spec
  (:require
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-cases-runner :refer [deftc]]
   [com.fulcrologic.guardrails-analyzer.test-checkers :as tc]
   [com.fulcrologic.guardrails.core :as gr]
   [com.fulcrologic.guardrails.malli.core :as malli]
   [fulcro-spec.check :as _]))

;; A spec1 function
(gr/>defn spec-inc [x] [int? => int?]
          (inc x))

;; A malli function calling a spec1 function
(malli/>defn malli-calls-spec [x] [:int => :int]            ; :binding/mixed.malli-calls-spec
             (spec-inc x))

;; A malli function with a bad call to a spec1 function
(malli/>defn malli-bad-call [x] [:string => :int]
             (spec-inc x))                                  ; :problem/mixed.malli-bad-call

;; A spec1 function calling a malli function
(gr/>defn spec-calls-malli [x] [int? => int?]               ; :binding/mixed.spec-calls-malli
          (malli-calls-spec x))

(deftc
  {:binding/mixed.malli-calls-spec
   {:message  "Malli function can call spec1 function and get valid samples"
    :expected (_/embeds?* {::cp.art/samples (_/every?* (_/is?* int?))})}

   :problem/mixed.malli-bad-call
   {:message  "Malli function passing wrong type to spec1 function is caught"
    :expected {::cp.art/problem-type :error/function-argument-failed-spec}}

   :binding/mixed.spec-calls-malli
   {:message  "Spec1 function can call malli function and get valid samples"
    :expected (_/embeds?* {::cp.art/samples (_/every?* (_/is?* int?))})}})
