(ns com.fulcrologic.guardrails-pro.analysis.analyzer.ifn-spec
  (:require
    com.fulcrologic.guardrails-pro.ftags.clojure-core ;; NOTE: required
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.ifn :as grp.ana.ifn]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [com.fulcrologic.guardrails-pro.test-checkers :as tc]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions component]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "random-samples"
  (let [env (tf/test-env)]
    (assertions
      (grp.ana.ifn/random-samples env
        [{::grp.art/samples #{1 2 3}}
         {::grp.art/samples #{:a :b :c}}])
      =check=> (_/every?*
                 (_/seq-matches?*
                   [(_/is?* number?)
                    (_/is?* keyword?)])))))

;; TASK: non literals
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
    #_(component "ifn?"
        (assertions
          ; defrecord
          (grp.ana/analyze! env '((reify) {:r 6}))))))
