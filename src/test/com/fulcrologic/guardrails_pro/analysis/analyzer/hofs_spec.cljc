(ns com.fulcrologic.guardrails-pro.analysis.analyzer.hofs-spec
  (:require
    com.fulcrologic.guardrails-pro.ftags.clojure-core ;; NOTE: required
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.hofs :as grp.ana.hofs]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "analyze-constantly!" :integration
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `((constantly :ok) "foo" "bar"))
      =check=> (_/embeds?* {::grp.art/samples #{:ok}}))))

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

#_(specification "analyze-comp!" :integration
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `(() "str"))
      =check=> (_/embeds?* {::grp.art/samples #{true}})
      )))

(specification "analyze-fnil!" :integration
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `((fnil + 1000) 7))
      =check=> (_/embeds?* {::grp.art/samples #{7}})
      "nil patches arguments"
      (grp.ana/analyze! env
        `((fnil + 1000) nil))
      =check=> (_/embeds?* {::grp.art/samples #{1000}})
      "fnil does not take nil nil-patches"
      (tf/capture-errors grp.ana/analyze! env
        `((fnil + nil) 100))
      =check=>
      (_/seq-matches?*
        [(_/embeds?* {::grp.art/problem-type :error/function-arguments-failed-spec})])
      "checks nil patches with function specs"
      (tf/capture-errors grp.ana/analyze! env
        `((fnil + :kw) nil))
      =check=>
      (_/seq-matches?*
        [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})]))))

#_(specification "analyze-juxt!" :integration
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `(() "str"))
      =check=> (_/embeds?* {::grp.art/samples #{true}})
      )))

#_(specification "analyze-partial!" :integration
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `(() "str"))
      =check=> (_/embeds?* {::grp.art/samples #{true}})
      )))
