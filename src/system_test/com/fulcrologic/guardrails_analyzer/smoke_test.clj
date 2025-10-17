(ns com.fulcrologic.guardrails-analyzer.smoke-test
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
    [com.fulcrologic.guardrails-analyzer.test-cases-runner :refer [deftc]]
    [com.fulcrologic.guardrails.core :refer [=> >defn]]
    [fulcro-spec.check :as _]))

(s/def ::binary (s/with-gen int? #(s/gen #{0 1})))

(>defn t [i] [::binary => string?] :kw)                     ; :binding/int :problem/defn.invalid-return

(deftc
  {:problem/defn.invalid-return
   {:expected {::cp.art/problem-type :error/bad-return-value
               ::cp.art/expression   "t"}}

   :binding/int
   {:expected (_/embeds?*
                {::cp.art/samples #{"0" "1"}})}})
