(ns com.fulcrologic.copilot.analysis.analyzer.ifn-spec
  (:require
    com.fulcrologic.copilot.ftags.clojure-core              ;; NOTE: required
    com.fulcrologic.copilot.analysis.analyzer.ifn
    [com.fulcrologic.copilot.analysis.analyze-test-utils :as cp.atu]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [fulcro-spec.core :refer [specification assertions component]]))

;; (tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "analyze ifn"
  (let [env (tf/test-env)]
    (component "not ifn?"
      (assertions
        (cp.atu/analyze-string! env "(-1 {})")
        => {::cp.art/unknown-expression -1}))
    (component "keywords"
      (assertions
        (cp.atu/analyze-string! env "(:a {:a 0})")
        => {::cp.art/samples #{0}}
        (cp.atu/analyze-string! env "(:a {} 1)")
        => {::cp.art/samples #{1}}))
    (component "symbols"
      (assertions
        (cp.atu/analyze-string! env "('a# {'a# 2})")
        => {::cp.art/samples #{2}}
        (cp.atu/analyze-string! env "('a# {} 3)")
        => {::cp.art/samples #{3}}))
    (component "maps"
      (assertions
        (cp.atu/analyze-string! env "({:kw 4} :kw)")
        => {::cp.art/samples #{4}}
        (cp.atu/analyze-string! env "({} :kw 5)")
        => {::cp.art/samples #{5}}))
    (component "sets"
      (assertions
        (cp.atu/analyze-string! env "(#{:x} :x)")
        => {::cp.art/samples #{:x}}))
    ;; FIXME maybe?
    #_(component "ifn?"
      (assertions
        (cp.atu/analyze-string! env
          ;; NOTE: bit hacky, but works
          "((reify clojure.lang.IFn
               (invoke [this x] \"INVOKED\")
               (applyTo [this xs] \"APPLIED\"))
             {:r 6})")
        => {::cp.art/samples #{"APPLIED"}}))))
