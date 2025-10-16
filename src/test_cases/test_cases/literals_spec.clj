(ns test-cases.literals-spec
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [com.fulcrologic.guardrails.core :refer [=> >defn]]
    [fulcro-spec.check :as _]))

(>defn sets [] [=> any?]
  (let [_ #{"str" :kw 123}                                  ; :binding/set
        _ #{:always (rand-nth [:a :b])}                     ; :binding/set.mixed
        _ #{(rand-nth [1 2]) (rand-nth [:a :b])}            ; :binding/set.random
        ]))

(>defn maps [] [=> any?]
  (let [a {:a {:b :c}}                                      ; :binding/map
        _ {:d a}                                            ; :binding/map.nested
        ; _ {:g (rand-nth [0 1 2])} ; #_:binding/map.random
        ]))

(deftc
  {:binding/set
   {:expected (_/embeds?* {::cp.art/samples (tc/subset?* #{#{"str" :kw 123}})})}

   :binding/set.mixed
   {:expected (_/embeds?* {::cp.art/samples (tc/subset?* #{#{:always :a} #{:always :b}})})}

   :binding/set.random
   {:expected (_/embeds?* {::cp.art/samples (tc/subset?* #{#{1 :a} #{1 :b} #{2 :a} #{2 :b}})})}

   :binding/map
   {:expected (_/embeds?* {::cp.art/samples (tc/subset?* #{{:a {:b :c}}})})}

   :binding/map.nested
   {:expected (_/embeds?* {::cp.art/samples (tc/subset?* #{{:d {:a {:b :c}}}})})}

   ; #_ :binding/map.random
   ; #_ {:expected {::cp.art/samples #{{:g 0} {:g 1} {:g 2}}}}
   })
