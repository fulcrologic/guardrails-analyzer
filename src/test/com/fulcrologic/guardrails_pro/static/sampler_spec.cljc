(ns com.fulcrologic.guardrails-pro.static.sampler-spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.sampler :as grp.sampler]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.test-checkers :as check :refer [check!]]
    [fulcro-spec.core :refer [specification component assertions when-mocking! =fn=>]]))

(defn with-mocked-errors [cb]
  (let [errors (atom [])]
    (when-mocking!
      (grp.art/record-error! _ error) => (swap! errors conj error)
      (cb errors))))

(specification "try-sampling!"
  (let [env (grp.art/build-env)]
    (with-mocked-errors
      (fn [errors]
        (grp.sampler/try-sampling! env
          (gen/fmap #(assoc % :k :v) (s/gen int?))
          {::grp.art/original-expression :TEST})
        (assertions
          @errors => [#::grp.art{:message "Failed to generate samples!"
                                 :original-expression :TEST}])))))

(specification "return-sample-generator"
  (let [env (grp.art/build-env)]
    (assertions
      (grp.sampler/return-sample-generator env nil {:return-sample ::test-sample})
      => ::test-sample
      (grp.sampler/return-sample-generator env :pure {:fn-ref str :args ["pure" \: "test"]})
      => "pure:test")))

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
