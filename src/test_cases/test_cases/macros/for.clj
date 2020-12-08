(ns test-cases.macros.for
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [fulcro-spec.check :as _]))

(>defn t [] [=> coll?]
  (for [x :kw] x) ; :problem/not-a-coll
  (let [_ (for [x (range 5)] x) ; :binding/for.range
        _ (for [x (range 3) :let [y "x="]] (str y x)) ; :binding/for.let
        _ (for [x# [:a :b] y# (range 2)] (vector x# y#)) ; :binding/for.two
        ]
    []))

(deftc
  {:problem/not-a-coll {:expected {::cp.art/problem-type :error/expected-seqable-collection}}
   :binding/for.range  {:expected (_/in* [::cp.art/samples]
                                    (_/fmap* first
                                      (tc/subset?* (set (range 5)))))}
   :binding/for.let    {:message "can bind values using `:let [...]`"
                        :expected (_/in* [::cp.art/samples]
                                    (tc/fmap* first
                                      (tc/subset?* #{"x=0" "x=1" "x=2"})))}
   :binding/for.two    {:message "multiple sequences permute"
                        :expected (_/in* [::cp.art/samples]
                                    (tc/fmap* first
                                      (_/all*
                                        (_/is?* vector?)
                                        (tc/subset?* #{[:a 0] [:a 1] [:b 0] [:b 1]}))))}})
