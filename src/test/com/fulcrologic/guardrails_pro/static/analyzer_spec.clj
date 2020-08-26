(ns com.fulcrologic.guardrails-pro.static.analyzer-spec
  (:require
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.analyzer :as grp.ana]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [fulcro-spec.core :refer [specification component assertions when-mocking!]]))

(grp/>defn test_int->int [x]
  [int? => int?]
  (inc x))

(specification "analyze"
  (component "A simple let"
    (let [errors (atom [])]
      (when-mocking!
        (grp.art/record-error! _ problem) => (swap! errors conj problem)
        (let [env (grp.art/build-env)]
          (grp.ana/analyze! env `(let [a# :a-kw] (test_int->int a#)))
          (assertions
            "It finds an error"
            (count @errors) => 1))))))
