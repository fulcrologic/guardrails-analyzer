(ns com.fulcrologic.copilot.analysis.analyzer.literals-spec
  (:require
    com.fulcrologic.copilot.analysis.analyzer.literals
    [com.fulcrologic.copilot.analysis.analyze-test-utils :as cp.atu]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [clojure.test]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions]]))

;; (tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "analyze-set!"
  (let [env (tf/test-env)]
    (assertions
      (cp.atu/analyze-string! env
        "(do #{\"str\" :kw 123})")
      =check=> (_/embeds?*
                 {::cp.art/samples #{#{"str" :kw 123}}})
      (cp.atu/analyze-string! env
        "(do #{:always (rand-nth [:a :b])})")
      =check=> (_/embeds?*
                 {::cp.art/samples #{#{:always :a} #{:always :b}}})
      (cp.atu/analyze-string! env
        "(do #{(rand-nth [1 2]) (rand-nth [:a :b])})")
      =check=> (_/embeds?*
                 {::cp.art/samples #{#{1 :a} #{1 :b} #{2 :a} #{2 :b}}}))))
