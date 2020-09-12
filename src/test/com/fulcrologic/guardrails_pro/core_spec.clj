(ns com.fulcrologic.guardrails-pro.core-spec
  (:require
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [com.fulcrologic.guardrails-pro.analysis.interpreter :as grp.int]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [fulcro-spec.core :refer [specification assertions when-mocking!]]))

(grp/>ftag clojure.core/inc [x] [number? => number?])

(grp/>defn a-test
  [x]
  [any? => string?]
  (inc x))

(defn with-mocked-errors [cb]
  (let [errors (atom [])]
    (when-mocking!
      (grp.art/record-error! _ error) => (swap! errors conj error)
      (cb errors))))

;; FIXME
#_(specification ">fdef"
  (with-mocked-errors
    (fn [errors] (grp.int/check! `a-test)
      (assertions
        @errors => 1))))
