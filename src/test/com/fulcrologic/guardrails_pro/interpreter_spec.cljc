(ns com.fulcrologic.guardrails-pro.interpreter-spec
  (:require
    [com.fulcrologic.guardrails-pro.core :as grp]
    [com.fulcrologic.guardrails-pro.interpreter :refer [check!]]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [fulcro-spec.core :refer [specification component assertions behavior =fn=> when-mocking!]]))

(grp/>defn g [x]
  [string? => string?]
  "hello")

(grp/>defn f [x]
  [int? => int?]
  (let [a x]
    (g a)))

#_(specification "Checking a function"
    (let [errors (atom [])]
      (when-mocking!
        (grp.art/record-error! _ problem) => (swap! errors conj problem)

        (check! (grp.art/build-env) `f))
      (assertions
        "finds errors"
        @errors => ["boo"])))

(comment
  )
