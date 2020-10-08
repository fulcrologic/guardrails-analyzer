(ns com.fulcrologic.guardrails-pro.analysis.analyzer.hofs-spec
  (:require
    com.fulcrologic.guardrails-pro.ftags.clojure-core ;; NOTE: required
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.hofs :as grp.ana.hofs]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [com.fulcrologic.guardrails.core :as gr]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions provided!]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "analyze-apply!" :integration
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `(apply + 1 [2 3]))
      =check=> (_/embeds?* {::grp.art/samples #{6}})
      "validates that apply was called correctly"
      (tf/capture-errors grp.ana/analyze! env
        `(apply + 1 2 3))
      =check=>
      (_/seq-matches?*
        [(_/embeds?* {::grp.art/problem-type :error/function-arguments-failed-predicate})])
      "validates single arguments wrt function"
      (tf/capture-errors grp.ana/analyze! env
        `(apply + :kw []))
      =check=>
      (_/seq-matches?*
        [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})])
      "validates the collection argument wrt function"
      (tf/capture-errors grp.ana/analyze! env
        `(apply + 1 2 [:kw]))
      =check=>
      (_/seq-matches?*
        [(_/embeds?* {::grp.art/problem-type :error/function-arguments-failed-spec})]))))

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
      "validates fnil itself received valid arguments"
      (tf/capture-errors grp.ana/analyze! env
        `(fnil "error" 1000))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})])
      (grp.ana/analyze! env
        `(fnil "error" 1000))
      => {}
      "nil patches arguments"
      (grp.ana/analyze! env
        `((fnil + 1000) nil))
      =check=> (_/embeds?* {::grp.art/samples #{1000}})
      "fnil does not take nil nil-patches"
      (tf/capture-errors grp.ana/analyze! env
        `(fnil + nil))
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

(specification "analyze-partial!" :integration
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `((partial + 1) 2))
      =check=> (_/embeds?* {::grp.art/samples #{3}})
      "validates partial itself received valid arguments"
      (tf/capture-errors grp.ana/analyze! env
        `(partial "error" 1))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})])
      (grp.ana/analyze! env
        `(partial "error" 1))
      => {}
      "checks arguments to partial itself against the function's specs"
      (tf/capture-errors grp.ana/analyze! env
        `((partial + "err") 1))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})])
      "checks arguments to the returned function against the function's specs"
      (tf/capture-errors grp.ana/analyze! env
        `((partial + 1) "err"))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})])
      "can partial varargs arguments"
      (grp.ana/analyze! env
        `((partial + 1 2 3) 100))
      =check=> (_/embeds?* {::grp.art/samples #{106}})
      (tf/capture-errors grp.ana/analyze! env
        `((partial + 1 2 "err") 100))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/function-arguments-failed-spec})])
      (tf/capture-errors grp.ana/analyze! env
        `((partial + 1 2 3) "err"))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/function-arguments-failed-spec})]))
    (provided! "the varargs spec is an s/cat"
      (grp.ana.hofs/analyze-lambda! _ _)
      => (let [gspec #::grp.art{:argument-specs [(s/cat :int int? :nums (s/+ number?))]
                                :argument-types ["(s/cat :int int? :nums (s/+ number?))"]
                                :sampler        ::grp.sampler/pure
                                :return-spec    number?
                                :return-type    "number?"}]
           #::grp.art{:lambda-name 'mock-lambda
                      :fn-ref (fn [& args] (apply + args))
                      :arities {:n #::grp.art{:arglist '[& xs] :gspec gspec}}})
      (assertions
        "can still partial its arguments"
        (grp.ana/analyze! env
          `((partial (gr/>fn mock-lambda) 1) 2 3))
        =check=> (_/embeds?* {::grp.art/samples #{6}})
        (tf/capture-errors grp.ana/analyze! env
          `((partial (gr/>fn mock-lambda) 1.0) 3))
        =check=> (_/seq-matches?*
                   [(_/embeds?* {::grp.art/problem-type :error/function-arguments-failed-spec})])))))
