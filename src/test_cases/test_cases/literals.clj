(ns test-cases.literals
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]))

(>defn sets [] [=> any?]
  (let [_ #{"str" :kw 123} ; :binding/set
        _ #{:always (rand-nth [:a :b])} ; :binding/set.mixed
        _ #{(rand-nth [1 2]) (rand-nth [:a :b])} ; :binding/set.random
        ]))

(>defn maps [] [=> any?]
  (let [a {:a {:b :c}} ; :binding/map
        _ {:d a} ; :binding/map.nested
        ; _ {:g (rand-nth [0 1 2])} ; #_:binding/map.random
        ]))

(deftc
  {:binding/set
   {:expected {::cp.art/samples #{#{"str" :kw 123}}}}

   :binding/set.mixed
   {:expected {::cp.art/samples #{#{:always :a} #{:always :b}}}}

   :binding/set.random
   {:expected {::cp.art/samples #{#{1 :a} #{1 :b} #{2 :a} #{2 :b}}}}

   :binding/map
   {:expected {::cp.art/samples #{{:a {:b :c}}}}}

   :binding/map.nested
   {:expected {::cp.art/samples #{{:d {:a {:b :c}}}}}}

   ; #_ :binding/map.random
   ; #_ {:expected {::cp.art/samples #{{:g 0} {:g 1} {:g 2}}}}
   })
