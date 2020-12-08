(ns runner
  (:require
    [com.fulcrologic.copilot.test-cases-runner :as tcr]
    [fulcro-spec.core :refer [specification]]))

(specification "TestCasesRunner" :test-case
  (tcr/check-test-cases! {:dir "src/test_cases/test_cases"}))
