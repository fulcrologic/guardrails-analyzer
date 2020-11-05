(ns com.fulcrologic.guardrails-pro.daemon.server.bindings-spec
  (:require
    com.fulcrologic.guardrails-pro.ftags.clojure-core ;; NOTE: required
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.daemon.server.bindings :refer [encode-for]]
    [com.fulcrologic.guardrails-pro.test-checkers :as tc]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [fulcro-spec.core :refer [specification assertions component]]
    [fulcro-spec.check :as _]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn test:encode-for [viewer-type sexpr]
  (grp.art/clear-bindings!)
  (grp.ana/analyze! (tf/test-env) sexpr)
  (encode-for {:viewer-type viewer-type}
    @grp.art/bindings))

(specification "encode-for" :integration :wip
  (component "viewer: IDEA"
    (assertions
      (test:encode-for :IDEA `(let [a# 1] a#))
      =check=> (_/embeds?* {"fake-file" {1 {1 (tc/of-length?* 1)}}}))))
