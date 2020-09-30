(ns com.fulcrologic.guardrails-pro.analysis.sampler-spec
  (:require
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [com.fulcrologic.guardrails-pro.test-checkers :as tc]
    [fulcro-spec.check :as _ :refer [checker]]
    [fulcro-spec.core :refer [specification component assertions when-mocking]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "convert-shorthand-metadata"
  (assertions
    "rewrites simple keywords"
    (grp.sampler/convert-shorthand-metadata {:pure? 0})
    => {::grp.sampler/pure 0}
    (grp.sampler/convert-shorthand-metadata {:pure 1})
    => {::grp.sampler/pure 1}
    (grp.sampler/convert-shorthand-metadata {:merge-arg 2})
    => {::grp.sampler/merge-arg 2}
    "passes through unknown metadata"
    (grp.sampler/convert-shorthand-metadata {:stuff "things"})
    => {:stuff "things"})
  (component "if the incoming map contains ::grp.sampler"
    (assertions
      "it rewrites a simple keyword"
      (grp.sampler/convert-shorthand-metadata {::grp.sampler/sampler :pure})
      => {::grp.sampler/sampler ::grp.sampler/pure}
      "it rewrites a vector's first element"
      (grp.sampler/convert-shorthand-metadata {::grp.sampler/sampler [:merge-arg 3]})
      => {::grp.sampler/sampler [::grp.sampler/merge-arg 3]}
      "still rewrites all other keywords in the map"
      (grp.sampler/convert-shorthand-metadata {::grp.sampler/sampler :pure :pure 4 :foo :bar})
      => {::grp.sampler/sampler ::grp.sampler/pure
          ::grp.sampler/pure    4
          :foo                  :bar})))

(specification "derive-sampler-type"
  (assertions
    "returns the first valid sampler if there are multiple"
    (grp.sampler/derive-sampler-type
      {::grp.sampler/pure true
       ::grp.sampler/merge-arg true})
    => ::grp.sampler/pure
    "returns nil if there are no valid samplers"
    (grp.sampler/derive-sampler-type
      {:foobar true})
    => nil
    "returns whatever is at ::grp.sampler/sampler"
    (grp.sampler/derive-sampler-type
      {::grp.sampler/sampler :pure})
    => :pure
    (grp.sampler/derive-sampler-type
      {::grp.sampler/sampler ::grp.sampler/pure})
    => ::grp.sampler/pure
    (grp.sampler/derive-sampler-type
      {::grp.sampler/sampler [:foobar 1]})
    => [:foobar 1]))

(specification "flatten-samples"
  (let [->S grp.sampler/->samples]
    (assertions
      (grp.sampler/flatten-samples
        #{(->S #{:a :b}) :c (->S #{:d :e}) [:f :g]})
      => #{:a :b :c :d :e [:f :g]})))

(specification "get-args"
  (let [env (grp.art/build-env)]
    (assertions
      (grp.sampler/get-args env {})
      =throws=> #"Failed to get samples"
      (grp.sampler/get-args env {::grp.art/samples #{}})
      =throws=> #"Failed to get samples"
      (grp.sampler/get-args env {::grp.art/samples #{123}})
      => #{123}
      (grp.sampler/get-args env {::grp.art/samples #{}
                                 ::grp.art/fn-ref identity})
      => [identity]
      (grp.sampler/get-args env {::grp.art/samples #{123}
                                 ::grp.art/fn-ref identity})
      => #{123})))

(specification "samples-gen"
  (let [env (grp.art/build-env)]
    (assertions
      (gen/sample (grp.sampler/args-gen env [#{:a :b :c}]))
      =check=> (_/every?*
                 (tc/of-length?* 1)
                 (_/seq-matches?*
                   [(_/is?* #{:a :b :c})]))
      (gen/sample (grp.sampler/args-gen env [#{:a :b :c}
                                             #{1 2 3}]))
      =check=> (_/every?*
                 (tc/of-length?* 2)
                 (_/seq-matches?*
                   [(_/is?* #{:a :b :c})
                    (_/is?* #{1 2 3})]))
      (gen/sample (grp.sampler/args-gen env [[identity]
                                             #{:a :b :c}]))
      =check=> (_/every?*
                 (tc/of-length?* 2)
                 (_/seq-matches?*
                   [(_/equals?* identity)
                    (_/is?* #{:a :b :c})])))))

(specification "params-gen" :WIP
  (let [env (grp.art/build-env) ]
    (when-mocking
      (grp.sampler/get-gspec _ _)  =>  {::grp.art/sampler ::grp.sampler/pure
                                        ::grp.art/return-spec int?}
      (assertions
        (gen/sample (grp.sampler/params-gen env {::grp.art/fn-ref +} []))
        =check=> (_/every?*
                   (_/embeds?*
                     {:fn-ref (_/equals?* +)
                      :params nil
                      :argtypes []
                      :return-sample-fn (checker [f] ((_/is?* int?) (f)))}))))))

(specification "propagate-samples!"
  (let [env (grp.art/build-env)]
    (component "default"
      (assertions
        (grp.sampler/propagate-samples! env nil
          {:return-sample-fn (constantly ::test-sample)})
        => ::test-sample))
    (component "pure"
      (assertions
        (grp.sampler/propagate-samples! env ::grp.sampler/pure
          {:fn-ref str :args ["pure" \: "test"]})
        => "pure:test"))
    (component "merge-arg"
      (assertions
        (grp.sampler/propagate-samples! env [::grp.sampler/merge-arg 1]
          {:args [:db {:person/name "john"}]
           :params 1
           :return-sample-fn (constantly {:person/full-name "john doe"})})
        => #:person{:name "john" :full-name "john doe"}))
    (component "map-like"
      (assertions
        (grp.sampler/map-like-args env
          [{::grp.art/samples #{[1 2 3]}}
           {::grp.art/samples #{[4 5 6]}}])
        => [[1 4] [2 5] [3 6]]
        (grp.sampler/map-like-args env
          [{::grp.art/samples #{[1 2  ]}}
           {::grp.art/samples #{[4 5 6]}}])
        => [[1 4] [2 5]]
        (grp.sampler/map-like-args env
          [{::grp.art/samples #{1 2}}
           {::grp.art/samples #{[3 4]}}])
        =throws=> #"expects all sequence arguments to be sequences"
        (grp.sampler/propagate-samples! env ::grp.sampler/map-like
          {:args [+ [1 2 3] [4 5 6]]
           :argtypes [{::grp.art/fn-ref +
                       ::grp.art/arities {:n {::grp.art/arglist '[& nums]
                                              ::grp.art/gspec {::grp.art/return-spec number?
                                                               ::grp.art/return-type "number?"
                                                               ::grp.art/metadata {::grp.sampler/sampler :pure}}}}}
                      {::grp.art/samples #{[1 2 3]}}
                      {::grp.art/samples #{[4 5 6]}}]
           :gspec {::grp.art/sampler ::grp.sampler/pure}
           :return-sample-fn (constantly [666])})
        => #{[5 7 9]}))))

(specification "random-samples-from"
  (let [env (grp.art/build-env)]
    (assertions
      (grp.sampler/random-samples-from env
        {::grp.art/samples [1 2 3]}
        {::grp.art/samples [:a :b :c]})
      =check=> (_/every?* (_/is?* #{1 2 3 :a :b :c})))))
