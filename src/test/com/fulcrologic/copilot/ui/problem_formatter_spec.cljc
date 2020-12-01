(ns com.fulcrologic.copilot.ui.problem-formatter-spec
  (:require
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [com.fulcrologic.copilot.analysis.analyzer :as grp.ana]
    [com.fulcrologic.copilot.artifacts :as grp.art]
    [com.fulcrologic.copilot.ui.problem-formatter :refer [format-problem]]
    [fulcro-spec.core :refer [specification assertions]]))

;; (tf/use-fixtures :once tf/with-default-test-logging-config)

(defn test:format-problem [env x]
  (mapv (comp ::grp.art/message format-problem)
    (tf/capture-errors grp.ana/analyze! env x)))

#_(specification "format-problem" :integration
  (let [env (tf/test-env)]
    (assertions
      (test:format-problem env `(+ 1 2 :kw))
      => ["Function arguments <[:kw]> failed spec <(s/+ number?)>."])))
