(ns com.fulcrologic.copilot.analysis.analyzer.macros-spec
  (:require
    [com.fulcrologic.copilot.analysis.analyzer :as cp.ana]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [clojure.test]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions]]))

;; (tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "analyze for" :integration :wip
  (let [env (tf/test-env)]
    (assertions
      (cp.ana/analyze! env
        `(for [x# (range 5)] x#))
      =check=> (_/in* [::cp.art/samples]
                 (tc/fmap* first
                   (tc/subset?* #{0 1 2 3 4})))
      "can bind values using `:let [...]`"
      (cp.ana/analyze! env
        `(for [x# (range 3)
               :let [y# "x="]]
           (str y# x#)))
      =check=> (_/in* [::cp.art/samples]
                 (tc/fmap* first
                   (tc/subset?* #{"x=0" "x=1" "x=2"})))
      "errors if given non seqable values"
      (tf/capture-errors cp.ana/analyze! env
        `(for [x# :kw] x#))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::cp.art/problem-type :error/expected-seqable-collection})])
      "multiple sequences permute"
      (cp.ana/analyze! env
        `(for [x# [:a :b]
               y# (range 2)]
           (vector x# y#)))
      =check=> (_/in* [::cp.art/samples]
                 (tc/fmap* first
                   (_/all*
                     (_/is?* vector?)
                     (tc/subset?* #{[:a 0] [:a 1] [:b 0] [:b 1]})))))))

(specification "analyze if" :integration
  (let [env (tf/test-env)]
    (assertions
      (cp.ana/analyze! env
        `(if true :a :b))
      =check=> (_/embeds?*
                 {::cp.art/samples #{:a :b}})
      (tf/capture-warnings cp.ana/analyze! env
        `(if true :a :b))
      =check=> (_/all*
                 (tc/of-length?* 1)
                 (_/seq-matches?*
                   [(_/embeds?* {::cp.art/problem-type :warning/if-condition-never-reaches-else-branch})])))))
