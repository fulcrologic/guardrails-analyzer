(ns test-cases.>defn
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [fulcro-spec.check :as _]))

(>defn r1 [] [=> int?]
  "abc" ; :problem/defn.literal
  )

(>defn r2 [] [=> int?]
  (str 2) ; :problem/defn.expr
  )


(deftc
  {:problem/defn.literal {:expected (_/embeds?* {::cp.art/original-expression {:value "abc"}})}
   :problem/defn.expr    {:expected (_/embeds?* {::cp.art/original-expression
                                                 (_/seq-matches?* ['str (_/embeds?* {:value 2})])})}})
