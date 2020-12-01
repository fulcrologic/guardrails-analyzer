(ns com.fulcrologic.copilot.analysis.analyzer.hofs-spec
  (:require
    com.fulcrologic.copilot.ftags.clojure-core ;; NOTE: required
    [clojure.spec.alpha :as s]
    [com.fulcrologic.copilot.analysis.analyzer :as grp.ana]
    [com.fulcrologic.copilot.artifacts :as grp.art]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [clojure.test]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions behavior]]))

;; (tf/use-fixtures :once tf/with-default-test-logging-config)

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
      "if did not validate, still returns valid samples"
      (grp.ana/analyze! env
        `(apply + 1 2 3))
      =check=> (_/embeds?* {::grp.art/samples (_/every?* (_/is?* number?))})
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

(specification "analyze-comp!" :integration :wip
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `((comp inc inc) 0))
      =check=> (_/embeds?* {::grp.art/samples #{2}})
      (tf/capture-errors grp.ana/analyze! env
        `((comp inc str) 0))
      =check=>
      (_/all*
        (tc/of-length?* 1)
        (_/seq-matches?*
          [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})]))
      (grp.ana/analyze! env
        `((comp inc str) 0))
      =check=>
      (_/embeds?* {::grp.art/samples (_/every?* (_/is?* number?))}))))

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
      "if not passed a function, returns an empty type description"
      (grp.ana/analyze! env
        `(fnil "error" 1000))
      => {}
      "if the arguments failed validation, returns the function as is"
      (grp.ana/analyze! env
        `((fnil + "str") 1 2))
      =check=> (_/embeds?* {::grp.art/samples #{3}})
      "nil patches arguments"
      (grp.ana/analyze! env
        `((fnil + 1000) nil))
      =check=> (_/embeds?* {::grp.art/samples #{1000}})
      "fnil can take nil nil-patches"
      (grp.ana/analyze! env
        `((fnil str nil 2 3) nil nil ":three"))
      =check=> (_/embeds?* {::grp.art/samples #{"2:three"}})
      "fnil only takes at most 3 arguments"
      (tf/capture-errors grp.ana/analyze! env
        `(fnil + 1 2 3 4))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/function-arguments-failed-predicate})])
      "checks nil patches with function specs"
      (tf/capture-errors grp.ana/analyze! env
        `((fnil + :kw) nil))
      =check=>
      (_/seq-matches?*
        [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})]))))

#_(specification "analyze-juxt!" :integration :wip
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `(juxt inc dec))
      => {}
      )))

(specification "analyze-partial!" :integration
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `((partial + 1) 2))
      =check=> (_/embeds?* {::grp.art/samples #{3}})
      "if not passed a function: errors and returns an empty type description"
      (tf/capture-errors grp.ana/analyze! env
        `(partial "error" 1))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})])
      (grp.ana/analyze! env
        `(partial "error" 1))
      => {}
      "checks arguments to partial itself against the function's specs"
      (tf/capture-errors grp.ana/analyze! env
        `(partial + "err"))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type        :error/invalid-partially-applied-arguments
                               ::grp.art/original-expression ["err"]})])
      (grp.ana/analyze! env
        `(partial + "err"))
      =check=> (_/equals?* {})
      "checks arguments to the returned function against the function's specs"
      (tf/capture-errors grp.ana/analyze! env
        `((partial + 1) "err"))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})])
      (grp.ana/analyze! env
        `((partial + 1) "err"))
      =check=> (_/embeds?* {::grp.art/samples (_/every?* (_/is?* number?))})
      "can partial varargs arguments"
      (grp.ana/analyze! env
        `((partial + 1 2 3) 100))
      =check=> (_/embeds?* {::grp.art/samples #{106}})
      (tf/capture-errors grp.ana/analyze! env
        `(partial + 1 2 "err"))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/invalid-partially-applied-arguments})])
      (tf/capture-errors grp.ana/analyze! env
        `((partial + 1 2 3) "err"))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/function-arguments-failed-spec})]))
    (behavior "the varargs spec is an s/cat"
      (let [lambda (tc/>test-fn [& args]
                     ^:pure [(s/cat :int int? :nums (s/* number?)) :ret number?]
                     (apply + args))]
        (assertions
          "can still partial its arguments"
          (grp.ana/analyze! env
            `((partial ~lambda 1) 2 3))
          =check=> (_/embeds?* {::grp.art/samples #{6}})
          (tf/capture-errors grp.ana/analyze! env
            `(partial ~lambda 1.0))
          =check=> (_/seq-matches?*
                     [(_/embeds?* {::grp.art/problem-type :error/invalid-partially-applied-arguments})]))))))

(specification "analyze-reduce!" :integration :wip
  (let [env (tf/test-env)
        sum-fn (tc/>test-fn [acc x]
                 ^:pure [number? number? :ret number?]
                 (+ acc x))]
    (assertions
      (grp.ana/analyze! env
        `(reduce ~sum-fn 0 (range 5)))
      =check=> (_/embeds?* {::grp.art/samples #{10}}))))

(specification "analyze-some!" :integration :wip
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `(some #{:kw} ["str" :kw 123]))
      =check=> (_/embeds?*
                 {::grp.art/samples #{:kw}})
      (grp.ana/analyze! env
        `(some #{(rand-nth ["str" :kw])} ["str" :kw 123]))
      =check=> (_/embeds?*
                 {::grp.art/samples #{"str" :kw}}))))

(specification "analyze-split-with!" :integration :wip
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `(split-with keyword? [:a :b 1 2]))
      =check=> (_/embeds?*
                 {::grp.art/samples #{[[:a :b] [1 2]]}})
      (grp.ana/analyze! env
        `(split-with string? :not-a-coll))
      =check=> (_/embeds?*
                 {::grp.art/samples (_/every?*
                                      (_/is?* vector?)
                                      (tc/of-length?* 2)
                                      (_/every?*
                                        (_/is?* sequential?)))})
      (grp.ana/analyze! env
        `(split-with "err" [:foo 44]))
      =check=> (_/embeds?*
                 {::grp.art/samples (_/every?*
                                      (_/is?* vector?)
                                      (tc/of-length?* 2)
                                      (_/every?*
                                        (_/is?* sequential?)))}))))

(specification "analyze-swap!" :integration :wip
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `(swap! (atom nil) + 100))
      =check=> (_/embeds?* {::grp.art/samples (_/every?* (_/is?* number?))})
      (tf/capture-errors grp.ana/analyze! env
        `(swap! (atom nil) + :kw))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})]))))

(specification "analyze-update!" :integration :wip
  (let [env (tf/test-env)]
    (assertions
      (grp.ana/analyze! env
        `(update {:a 0} :a inc))
      =check=> (_/embeds?* {::grp.art/samples #{{:a 1}}})
      (grp.ana/analyze! env
        `(update {:a 1} :a + :kw))
      =check=> (_/embeds?* {::grp.art/samples
                            (_/every?* (tc/fmap* :a (_/is?* number?)))})
      (tf/capture-errors grp.ana/analyze! env
        `(update {:a 1} :a + :kw))
      =check=> (_/seq-matches?*
                 [(_/embeds?* {::grp.art/problem-type :error/function-argument-failed-spec})]))))
