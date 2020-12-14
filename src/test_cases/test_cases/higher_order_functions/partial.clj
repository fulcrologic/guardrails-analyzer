(ns test-cases.higher-order-functions.partial
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [>defn => |]]
    [fulcro-spec.check :as _]))

(>defn ^:pure f [a b]
  [int? int? => int?]
  (+ a b))

(>defn t [] [=> any?]
  (let [pf1 (partial f 23 55)
        r1  (pf1)              ; :binding/partial.pf1.result
        pf2 (partial f :foo)   ; :problem/partial.pf2.bad-arg :problem/partial.pf2.failed
        ;r2  (pf2) ; :binding/FIXME
        ]))

(deftc
  {:binding/partial.pf1.result
   {:expected {::cp.art/samples #{78}}}

   :problem/partial.pf2.bad-arg
   {:expected (_/embeds?*
                {::cp.art/problem-type :error/invalid-partially-applied-arguments
                 ::cp.art/original-expression (_/seq-matches?* [(_/embeds?* {:value :foo})])
                 ::cp.art/message-params {:function "f"}})}

   :problem/partial.pf2.failed
   {:expected {::cp.art/problem-type :info/failed-to-analyze-unknown-expression}}

   })
