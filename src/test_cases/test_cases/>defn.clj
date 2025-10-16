(ns test-cases.>defn
  (:require
    [clojure.spec.alpha :as s :refer [tuple]]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [com.fulcrologic.guardrails.core :refer [=> >defn]]
    [fulcro-spec.check :as _]))

(>defn r1 [] [=> int?]                                      ; :problem/defn.literal
  "abc"
  )

(>defn r2 [] [=> int?]                                      ; :problem/defn.expr
  (str 2)
  )

(>defn ^:pure pure [x] [any? => vector?] [:pure x])

(>defn r3 [i] [int? => nil?] (pure i))                      ; :problem/defn.pure

(>defn pure-2
  ([] [=> vector?] [:not-pure])
  ([x] ^:pure [int? => map?] {:pure x}))

(>defn r4 [] [=> any?]
  (let [_ (pure-2)                                          ; :binding/defn.not-pure-2
        _ (pure-2 7)                                        ; :binding/defn.pure-2
        ]))

(>defn r5 [] [=> (s/keys :req [::foo])] {:foo 5})           ; :problem/defn.not-req-key

(s/def ::zero (s/with-gen zero? #(gen/return 0)))
(s/def ::one (s/with-gen #(= % 1) #(gen/return 1)))

(>defn r6 [[x y :as t]]                                     ; :binding/_ :binding/_ :binding/defn.referred :problem/defn.destructuring
  [(tuple ::zero ::one) => int?]
  (pr-str [x y :as t]))

(deftc
  {:binding/_ {}

   :problem/defn.literal
   {:expected (_/embeds?* {::cp.art/original-expression 'r1})}

   :problem/defn.expr
   {:expected (_/embeds?* {::cp.art/original-expression 'r2})}

   :problem/defn.pure
   {:message  "A defn can be marked pure on its symbol name"
    :expected (_/in* [::cp.art/actual ::cp.art/failing-samples]
                (tc/fmap* first
                  (_/seq-matches?*
                    [(_/equals?* :pure)
                     (_/is?* int?)])))}

   :binding/defn.not-pure-2
   {:message  "A defn's specific arity can be marked pure"
    :expected (_/embeds?* {::cp.art/samples (_/every?* (_/is?* vector?))})}

   :binding/defn.pure-2
   {:message  "A defn's specific arity can be marked pure"
    :expected {::cp.art/samples #{{:pure 7}}}}

   :problem/defn.not-req-key
   {:message  "A defn return value did not contain a required key"
    :expected {::cp.art/problem-type :error/bad-return-value}}

   :binding/defn.referred
   {:expected {::cp.art/samples #{[0 1]}}}

   :problem/defn.destructuring
   {:expected {::cp.art/problem-type :error/bad-return-value
               ::cp.art/actual       {::cp.art/failing-samples #{"[0 1 :as [0 1]]"}}}}
   })
