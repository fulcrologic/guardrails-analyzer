(ns com.fulcrologic.guardrails-pro.analysis.analyzer.ifn-spec
  (:require
    com.fulcrologic.guardrails-pro.ftags.clojure-core ;; NOTE: required
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [fulcro-spec.core :refer [specification assertions component]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "analyze ifn"
  (let [env (tf/test-env)]
    (component "not ifn?"
      (assertions
        (grp.ana/analyze! env '(-1 {}))
        => {::grp.art/unknown-expression '(-1 {})}) )
    (component "keywords"
      (assertions
        (grp.ana/analyze! env '(:a {:a 0}))
        => {::grp.art/samples #{0}}
        (grp.ana/analyze! env '(:a {} 1))
        => {::grp.art/samples #{1}}))
    (component "symbols"
      (assertions
        (grp.ana/analyze! env '('a# {a# 2}))
        => {::grp.art/samples #{2}}
        (grp.ana/analyze! env '('a# {} 3))
        => {::grp.art/samples #{3}}))
    (component "maps"
      (assertions
        (grp.ana/analyze! env '({:kw 4} :kw))
        => {::grp.art/samples #{4}}
        (grp.ana/analyze! env '({} :kw 5))
        => {::grp.art/samples #{5}}))
    (component "sets"
      (assertions
        (grp.ana/analyze! env '(#{:x} :x))
        => {::grp.art/samples #{:x}}))
    (component "ifn?"
      (assertions
        (grp.ana/analyze! env
          ;; NOTE: bit hacky, but works
          `(~(reify clojure.lang.IFn
               (invoke [this x] "INVOKED")
               (applyTo [this xs] "APPLIED"))
             {:r 6}))
        => {::grp.art/samples #{"APPLIED"}}))))
