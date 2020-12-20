(ns test-cases.destructuring
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [fulcro-spec.check :as _]))

(>defn d0 [] [=> any?]
  (let [_ :a ; :binding/destr.simple-symbol
        ]))

(s/def ::c int?)
(s/def ::f int?)

(>defn d:maps [] [=> any?]
  (let [{b :b}       {:b 1}  ; :binding/destr.map.simple-keyword
        {c ::c}      {::c 2} ; :binding/destr.map.nsed-keyword
        {c ::c}      {}      ; :problem/destr.map.nsed-keyword.missing
        {:as d}      {:d 3}  ; :binding/destr.map.as
        {::keys [f]} {::f 5} ; :binding/destr.map.nsed-keys
        {g ::g}      {}      ; :problem/destr.map.nsed-keyword.no-spec
        ]))

(>defn d:vectors [] [=> any?]
  (let [[a]      [6]      ; :binding/destr.vec.one
        [_ b]    [:b 7]   ; :binding/_ :binding/destr.vec.two
        [c]      []       ; :binding/destr.vec.missing
        [_ & ds] [8 9 10] ; :binding/_ :binding/destr.vec.amp
        [:as e]  [11 12]  ; :binding/destr.vec.as
        ]))

(>defn d:mixed [] [=> any?]
  (let [[{x :x} :as v] [{:x 13}] ; :binding/destr.mixed.x :binding/destr.mixed.as
        ]))

(deftc
  {:binding/destr.simple-symbol
              {:expected {::cp.art/samples #{:a}}}

   :binding/destr.map.simple-keyword
              {:expected {::cp.art/samples #{1}}}

   :binding/destr.map.nsed-keyword
              {:expected {::cp.art/samples #{2}}}

   :binding/destr.map.as
              {:expected {::cp.art/samples #{{:d 3}}}}

   :binding/destr.map.nsed-keys
              {:expected {::cp.art/samples #{5}}}

   :problem/destr.map.nsed-keyword.no-spec
              {:expected (_/embeds?*
                           {::cp.art/original-expression {:value ::g}
                            ::cp.art/problem-type        :warning/qualified-keyword-missing-spec})}

   :problem/destr.map.nsed-keyword.missing
              {:expected (_/embeds?*
                           {::cp.art/original-expression {:value ::c}
                            ::cp.art/problem-type        :warning/destructured-map-entry-may-not-be-present
                            ::cp.art/expected            {::cp.art/type (str ::c)}})}

   :binding/destr.vec.one
              {:expected {::cp.art/samples #{6}}}

   :binding/destr.vec.two
              {:expected {::cp.art/samples #{7}}}

   :binding/destr.vec.missing
              {:expected {::cp.art/samples #{nil}}}

   :binding/destr.vec.amp
              {:expected {::cp.art/samples #{[9 10]}}}

   :binding/destr.vec.as
              {:expected {::cp.art/samples #{[11 12]}}}

   :binding/destr.mixed.x
              {:expected {::cp.art/samples #{13}}}

   :binding/destr.mixed.as
              {:expected {::cp.art/samples #{[{:x 13}]}}}

   :binding/_ {}
   })
