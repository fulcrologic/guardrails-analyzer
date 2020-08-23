(ns com.fulcrologic.guardrails-pro.static.analyzer-spec
  (:require
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.static.analyzer :as src]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [clojure.java.io :as io]
    [fulcro-spec.core :refer [specification component assertions behavior =fn=> when-mocking!]]))

(grp/>defn f [x]
  [int? => int?]
  (inc x)
  "hello world")

(specification "analyze"
  (component "Function return type"
    (let [errors (atom [])]
     (when-mocking!
       (a/record-problem! env problem) => (swap! errors conj problem)

       (let [env (a/build-env)]

         (src/analyze! env `(let [~'a :a-kw] (f ~'a)))

         (assertions
           "It finds an error"
           (count @errors) => 1)))))
  (component "A simple let"
    (let [errors (atom [])]
      (when-mocking!
        (a/record-problem! env problem) => (swap! errors conj problem)

        (let [env (a/build-env)]

          (src/analyze! env `(let [~'a :a-kw] (f ~'a)))

          (assertions
            "It finds an error"
            (count @errors) => 1))))))
