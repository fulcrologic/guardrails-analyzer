(ns com.fulcrologic.guardrails-pro.interpreter-spec
  (:require
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :as grp :refer [>defn]]
    [com.fulcrologic.guardrails-pro.interpreter :refer [check!]]
    [clojure.java.io :as io]
    [fulcro-spec.core :refer [specification component assertions behavior =fn=> when-mocking!]]))

(>defn g [x]
  [string? => string?]
  "hello")

(>defn f [x]
  [int? => int?]
  (let [a x]
    (g a)))

#_(specification "Checking a function"
  (let [errors (atom [])]
    (when-mocking!
      (a/record-problem! e p) => (swap! errors conj p)

      (check! (a/build-env) `f))
    (assertions
      "finds errors"
      @errors => ["boo"])))

(comment
  )

