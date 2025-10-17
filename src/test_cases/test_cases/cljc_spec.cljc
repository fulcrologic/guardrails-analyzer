(ns test-cases.cljc-spec
  (:require
    [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
    [com.fulcrologic.guardrails-analyzer.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [=> >defn]]))

(>defn t [] [=> string?]                                    ; :problem/cljc.not-a-string
  #?(:clj 1 :cljs 2))

(deftc
  {:problem/cljc.not-a-string
   {:expected {::cp.art/problem-type :error/bad-return-value
               ::cp.art/actual       {::cp.art/failing-samples #{#?(:clj 1 :cljs 2)}}}}
   })
