(ns com.fulcrologic.copilot.analysis.analyzer.ifn-spec
  (:require
    com.fulcrologic.copilot.ftags.clojure-core              ;; NOTE: required
    [com.fulcrologic.copilot.analysis.analyzer :as cp.ana]
    [com.fulcrologic.copilot.analysis.analyzer.ifn]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [fulcro-spec.core :refer [specification assertions component]]))

;; (tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "analyze ifn"
  (let [env (tf/test-env)]
    (component "not ifn?"
      (assertions
        (cp.ana/analyze! env '(-1 {}))
        => {::cp.art/unknown-expression '(-1 {})}))
    (component "keywords"
      (assertions
        (cp.ana/analyze! env '(:a {:a 0}))
        => {::cp.art/samples #{0}}
        (cp.ana/analyze! env '(:a {} 1))
        => {::cp.art/samples #{1}}))
    (component "symbols"
      (assertions
        (cp.ana/analyze! env '('a# {a# 2}))
        => {::cp.art/samples #{2}}
        (cp.ana/analyze! env '('a# {} 3))
        => {::cp.art/samples #{3}}))
    (component "maps"
      (assertions
        (cp.ana/analyze! env '({:kw 4} :kw))
        => {::cp.art/samples #{4}}
        (cp.ana/analyze! env '({} :kw 5))
        => {::cp.art/samples #{5}}))
    (component "sets"
      (assertions
        (cp.ana/analyze! env '(#{:x} :x))
        => {::cp.art/samples #{:x}}))
    #?(:clj
       (component "ifn?"
         (assertions
           (cp.ana/analyze! env
             ;; NOTE: bit hacky, but works
             `(~(reify clojure.lang.IFn
                  (invoke [this x] "INVOKED")
                  (applyTo [this xs] "APPLIED"))
                {:r 6}))
           => {::cp.art/samples #{"APPLIED"}})))))
