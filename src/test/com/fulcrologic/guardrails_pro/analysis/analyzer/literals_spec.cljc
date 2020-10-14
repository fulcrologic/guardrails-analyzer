(ns com.fulcrologic.guardrails-pro.analysis.analyzer.literals-spec
  (:require
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "analyze-set!"
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `(do #{"str" :kw 123}))
      =check=> (_/embeds?*
                 {::grp.art/samples #{#{"str" :kw 123}}})
      (grp.ana/analyze! env
        `(do #{:always (rand-nth [:a :b])}))
      =check=> (_/embeds?*
                 {::grp.art/samples #{#{:always :a} #{:always :b}}})
      (grp.ana/analyze! env
        `(do #{(rand-nth [1 2]) (rand-nth [:a :b])}))
      =check=> (_/embeds?*
                 {::grp.art/samples #{#{1 :a} #{1 :b} #{2 :a} #{2 :b}}}))))
