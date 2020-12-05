(ns com.fulcrologic.copilot.test-cases-runner-spec
  (:require
    [com.fulcrologic.copilot.test-cases-runner :as tcr]
    [fulcro-spec.core :refer [specification assertions]]))

(specification "parse-test-cases" :unit
  (assertions
    (tcr/parse-test-cases ";assert: a") => [:a]
    (tcr/parse-test-cases "; assert: a,b") => [:a :b]
    (tcr/parse-test-cases "; assert: a, b") => [:a :b]))

(specification "test cases:"
  (tcr/check-test-cases!))
