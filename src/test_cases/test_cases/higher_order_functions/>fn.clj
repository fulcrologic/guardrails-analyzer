(ns test-cases.higher-order-functions.>fn
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [>defn >fn =>]]
    [fulcro-spec.check :as _]))

(>defn f1 [] [=> int?] ; :problem/lambda.bad-return
  ((>fn [x] [int? => keyword?] (str x)) 0)) ; :problem/lambda.sanity-check

(deftc
  {:problem/lambda.bad-return
   {:expected
    (_/embeds?* {::cp.art/problem-type :error/bad-return-value
                 ::cp.art/expected {::cp.art/type "int?"}
                 ::cp.art/actual {::cp.art/failing-samples
                                  (_/every?* (_/is?* keyword?))}})}

   :problem/lambda.sanity-check
   {:expected
    (_/embeds?*
      {::cp.art/problem-type :error/bad-return-value
       ::cp.art/actual {::cp.art/failing-samples
                        (_/every?* (_/is?* string?))}})}
   })
