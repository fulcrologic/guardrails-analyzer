(ns com.fulcrologic.guardrails-analyzer.ui.problem-formatter-spec
  (:require
    [com.fulcrologic.guardrails-analyzer.analysis.analyze-test-utils :as cp.atu]
    [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
    [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
    [com.fulcrologic.guardrails-analyzer.ui.problem-formatter :refer [format-problem]]
    [fulcro-spec.core :refer [assertions specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn test:format-problem [env x]
  (mapv (comp ::cp.art/message format-problem)
    (tf/capture-errors cp.atu/analyze-string! env x)))

#_(specification "format-problem" :integration
    (let [env (tf/test-env)]
      (assertions
        (test:format-problem env "(+ 1 2 :kw)")
        => ["Function arguments <[:kw]> failed spec <(s/+ number?)>."])))
