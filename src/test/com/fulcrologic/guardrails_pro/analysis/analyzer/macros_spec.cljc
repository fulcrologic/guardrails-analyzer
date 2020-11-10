(ns com.fulcrologic.guardrails-pro.analysis.analyzer.macros-spec
  (:require
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.test-checkers :as tc]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "analyze for" :integration :wip
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `(for [x# (range 5)] x#))
      =check=> (_/in* [::grp.art/samples]
                 (tc/fmap* first
                   (tc/subset?* #{0 1 2 3 4})))
      "can bind values using `:let [...]`"
      (grp.ana/analyze! env
        `(for [x# (range 3)
               :let [y# "x="]]
           (str y# x#)))
      =check=> (_/in* [::grp.art/samples]
                 (tc/fmap* first
                   (tc/subset?* #{"x=0" "x=1" "x=2"})))
      "errors if given non seqable values"
      (tf/capture-errors grp.ana/analyze! env
        `(for [x# :kw] x#))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/expected-seqable-collection})])
      "multiple sequences permute"
      (grp.ana/analyze! env
        `(for [x# [:a :b]
               y# (range 2)]
           (vector x# y#)))
      =check=> (_/in* [::grp.art/samples]
                 (tc/fmap* first
                   (_/all*
                     (_/is?* vector?)
                     (tc/subset?* #{[:a 0] [:a 1] [:b 0] [:b 1]})))))))
