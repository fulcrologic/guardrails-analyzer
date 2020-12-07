(ns ^{:test-case? false} runner
  (:require
    [clojure.test :as t]
    [com.fulcrologic.copilot.test-cases-runner :as tcr]))

(t/deftest TestCasesRunner
  (tcr/check-test-cases!))
