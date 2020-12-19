(ns test-cases.cljc
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]))

(>defn t [] [=> string?]
  #?(:clj 1 :cljs 2)) ; :problem/cljc.not-a-string

(deftc
  {:problem/cljc.not-a-string
   {:expected {::cp.art/problem-type :error/bad-return-value
               ::cp.art/actual {::cp.art/failing-samples #{#?(:clj 1 :cljs 2)}}}}
   })
