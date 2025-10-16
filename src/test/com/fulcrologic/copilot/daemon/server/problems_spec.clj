(ns com.fulcrologic.copilot.daemon.server.problems-spec
  (:require
    [com.fulcrologic.copilot.analysis.analyze-test-utils :as cp.atu]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.daemon.server.problems :refer [encode-for]]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn test:encode-for [viewer-type s]
  (cp.art/clear-problems!)
  (cp.atu/analyze-string! (tf/test-env) s)
  (encode-for {:viewer-type viewer-type}
    @cp.art/problems))

(specification "encode-for" :integration
  (component "viewer: IDEA"
    (assertions
      (test:encode-for :IDEA "(+ :kw)")
      =check=> (_/embeds?* {"fake-file" {1 {1 (tc/of-length?* 1)}}}))))
