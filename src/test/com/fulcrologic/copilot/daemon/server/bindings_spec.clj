(ns com.fulcrologic.copilot.daemon.server.bindings-spec
  (:require
    [com.fulcrologic.copilot.analysis.analyze-test-utils :as cp.atu]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.daemon.server.bindings :refer [encode-for]]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [fulcro-spec.core :refer [specification assertions component]]
    [fulcro-spec.check :as _]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn test:encode-for [viewer-type string]
  (cp.art/clear-bindings!)
  (cp.atu/analyze-string! (tf/test-env) string)
  (encode-for {:viewer-type viewer-type}
    @cp.art/bindings))

(specification "encode-for" :integration :wip
  (component "viewer: IDEA"
    (assertions
      (test:encode-for :IDEA "(let [a 1] a)")
      =check=> (_/embeds?* {"fake-file" {1 {1 (tc/of-length?* 1)}}}))))
