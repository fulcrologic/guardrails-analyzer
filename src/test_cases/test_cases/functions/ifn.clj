(ns test-cases.functions.ifn
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [fulcro-spec.check :as _]))

(>defn t [] [=> any?]
  (-1 {}) ; :problem/number.not-an-ifn
  (let
    [_ (:a {:a 0})   ; :binding/keyword
     _ (:a {} 1)     ; :binding/keyword-with-default
     _ ('a {'a 2}) ; :binding/symbol
     _ ('a {} 3)    ; :binding/symbol-with-default
     _ ({:kw 4} :kw) ; :binding/map
     _ ({} :kw 5)    ; :binding/map-with-default
     _ (#{6} 6)      ; :binding/set
     ]))

(deftc
  {:problem/number.not-an-ifn
   {:expected (_/embeds?*
                {::cp.art/problem-type :info/failed-to-analyze-unknown-expression
                 ::cp.art/original-expression
                 (_/seq-matches?* [(_/embeds?* {:value -1}) {}])})}

   :binding/keyword
   {:expected {::cp.art/samples #{0}}}

   :binding/keyword-with-default
   {:expected {::cp.art/samples #{1}}}

   :binding/symbol
   {:expected {::cp.art/samples #{2}}}

   :binding/symbol-with-default
   {:expected {::cp.art/samples #{3}}}

   :binding/map
   {:expected {::cp.art/samples #{4}}}

   :binding/map-with-default
   {:expected {::cp.art/samples #{5}}}

   :binding/set
   {:expected {::cp.art/samples #{6}}}
   })
