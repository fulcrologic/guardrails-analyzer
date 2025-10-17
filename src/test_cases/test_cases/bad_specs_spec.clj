(ns test-cases.bad-specs-spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
    [com.fulcrologic.guardrails-analyzer.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [=> >defn]]))

(>defn e1 [_ pi] [any? even? => any?])                      ; :problem/no-gen

(s/def ::impossible (s/with-gen #(= "impossible" %) #(gen/return "never")))

(>defn e2 [x] [::impossible => any?])                       ; :problem/not-generatable

(deftc
  {:problem/no-gen
   {:expected {::cp.art/original-expression 'pi
               ::cp.art/problem-type        :error/sample-generator-failed}}

   :problem/not-generatable
   {:expected {::cp.art/original-expression 'x
               ::cp.art/problem-type        :error/sample-generator-failed}}

   })
