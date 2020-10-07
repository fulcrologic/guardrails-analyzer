(ns com.fulcrologic.guardrails-pro.analysis.analyzer.hofs-spec
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    com.fulcrologic.guardrails-pro.ftags.clojure-core
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.hofs :as grp.ana.hofs]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "analyze-complement!" :integration
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `((complement symbol?) "str"))
      =check=> (_/embeds?* {::grp.art/samples #{true}})
      "checks function expected argument count"
      (tf/capture-errors grp.ana/analyze! env
        `((complement symbol?) "str" "bar"))
      =check=>
      (_/seq-matches?*
        [(_/embeds?* {::grp.art/problem-type :error/invalid-function-arguments-count})])
      "checks function expected argument specs"
      (tf/capture-errors grp.ana/analyze! env
        `((complement +) "str"))
      =check=>
      (_/seq-matches?*
        [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})]))))
