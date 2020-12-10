(ns test-cases.>defn
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [fulcro-spec.check :as _]))

(>defn r1 [] [=> int?]
  "abc" ; :problem/defn.literal
  )

(>defn r2 [] [=> int?]
  (str 2) ; :problem/defn.expr
  )

(>defn ^:pure pure [x] [any? => vector?] [:pure x])

(>defn r3 [i] [int? => nil?]
  (pure i)) ; :problem/defn.pure

(>defn pure-2
  ([] [=> vector?] [:not-pure])
  ([x] ^:pure [int? => map?] {:pure x}))

(>defn r4 [] [=> any?]
  (let [_ (pure-2) ; :binding/defn.not-pure-2
        _ (pure-2 7) ; :binding/defn.pure-2
        ]))

(deftc
  {:problem/defn.literal {:expected (_/embeds?* {::cp.art/original-expression {:value "abc"}})}
   :problem/defn.expr    {:expected (_/embeds?* {::cp.art/original-expression
                                                 (_/seq-matches?* ['str (_/embeds?* {:value 2})])})}
   :problem/defn.pure    {:message "A defn can be marked pure on its symbol name"
                          :expected (_/in* [::cp.art/actual ::cp.art/failing-samples]
                                      (tc/fmap* first
                                        (_/seq-matches?*
                                          [(_/equals?* :pure)
                                           (_/is?* int?)])))}
   :binding/defn.not-pure-2 {:message "A defn's specific arity can be marked pure"
                             :expected (_/embeds?* {::cp.art/samples (_/every?* (_/is?* vector?))})}
   :binding/defn.pure-2 {:message "A defn's specific arity can be marked pure"
                         :expected {::cp.art/samples #{{:pure 7}}}}})
