(ns test-cases.functions.get-in
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-cases-runner :refer [deftc]]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [fulcro-spec.check :as _]))

(s/def ::test-map (s/with-gen map?
                    #(gen/return
                       (zipmap
                         [:a :b :c]
                         (range)))))

(s/def ::test-path (s/with-gen vector?
                     #(s/gen #{[:a] [:b] [:c]})))

(s/def ::val (s/with-gen char? #(s/gen #{\x \y \z})))

(>defn t [m p] [::test-map ::test-path => any?] ; :binding/test-map :binding/test-path
  (let [_ (get-in {:a 0} [:a]) ; :binding/get-in.simple
        _ (get-in {} [:b])     ; :problem/get-in.missing :binding/get-in.missing
        _ (get-in {} [:b] 1)   ; :binding/get-in.missing.default :problem/might-never
        _ (get-in m p)         ; :binding/get-in.locals
        _ (get-in {} [::val])  ; :binding/get-in.fq-keyword :problem/might-never
        ]))

(deftc
  {:binding/test-map
   {:expected {::cp.art/samples #{{:a 0 :b 1 :c 2}}}}

   :binding/test-path
   {:expected (_/embeds?* {::cp.art/samples (tc/subset?* #{[:a] [:b] [:c]})})}

   :binding/get-in.simple
   {:expected {::cp.art/samples #{0}}}

   :problem/get-in.missing
   {:expected {::cp.art/problem-type :warning/get-in-might-never-succeed
               ::cp.art/original-expression :b}}

   :binding/get-in.missing
   {:expected {::cp.art/samples #{nil}}}

   :binding/get-in.missing.default
   {:expected {::cp.art/samples #{1}}}

   :problem/might-never
   {:expected {::cp.art/problem-type :warning/get-in-might-never-succeed}}

   :binding/get-in.locals
   {:expected (_/embeds?* {::cp.art/samples (tc/subset?* #{0 1 2})})}

   :binding/get-in.fq-keyword
   {:expected (_/embeds?* {::cp.art/samples (tc/subset?* #{\x \y \z})})}

   })
