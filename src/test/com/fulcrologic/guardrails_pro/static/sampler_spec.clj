(ns com.fulcrologic.guardrails-pro.static.sampler-spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.sampler :as grp.sampler]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [com.fulcrologic.guardrails-pro.test-checkers :as check :refer [check!]]
    [fulcro-spec.core :refer [specification component assertions when-mocking! =fn=>]]))

(specification "hashmap-permutation-generator"
  (assertions
    (gen/sample
      (grp.sampler/hashmap-permutation-generator
        {:foo [1 2 3]
         :bar [:a :b :c]}))
    =fn=> (check!
            (check/is?* seq?)
            (check/every?*
              (check/is?* map?)
              (check/is?* #(int? (:foo %)))
              (check/is?* #(keyword? (:bar %)))))))
