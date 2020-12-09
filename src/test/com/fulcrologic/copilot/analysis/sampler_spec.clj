(ns com.fulcrologic.copilot.analysis.sampler-spec
  (:require
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as cp.ana.disp]
    [com.fulcrologic.copilot.analysis.sampler :as cp.sampler]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [fulcro-spec.check :as _ :refer [checker]]
    [fulcro-spec.core :refer [specification component assertions when-mocking]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "convert-shorthand-metadata"
  (assertions
    "rewrites simple keywords"
    (cp.sampler/convert-shorthand-metadata {:pure? 0})
    => {::cp.sampler/pure 0}
    (cp.sampler/convert-shorthand-metadata {:pure 1})
    => {::cp.sampler/pure 1}
    (cp.sampler/convert-shorthand-metadata {:merge-arg 2})
    => {::cp.sampler/merge-arg 2}
    "passes through unknown metadata"
    (cp.sampler/convert-shorthand-metadata {:stuff "things"})
    => {:stuff "things"})
  (component "if the incoming map contains ::cp.sampler"
    (assertions
      "it rewrites a simple keyword"
      (cp.sampler/convert-shorthand-metadata {::cp.sampler/sampler :pure})
      => {::cp.sampler/sampler ::cp.sampler/pure}
      "it rewrites a vector's first element"
      (cp.sampler/convert-shorthand-metadata {::cp.sampler/sampler [:merge-arg 3]})
      => {::cp.sampler/sampler [::cp.sampler/merge-arg 3]}
      "still rewrites all other keywords in the map"
      (cp.sampler/convert-shorthand-metadata {::cp.sampler/sampler :pure :pure 4 :foo :bar})
      => {::cp.sampler/sampler ::cp.sampler/pure
          ::cp.sampler/pure    4
          :foo                  :bar})))

(specification "derive-sampler-type"
  (assertions
    "returns the first valid sampler if there are multiple"
    (cp.sampler/derive-sampler-type
      {::cp.sampler/pure      true
       ::cp.sampler/merge-arg true})
    => ::cp.sampler/pure
    "returns nil if there are no valid samplers"
    (cp.sampler/derive-sampler-type
      {:foobar true})
    => nil
    "returns whatever is at ::cp.sampler/sampler"
    (cp.sampler/derive-sampler-type
      {::cp.sampler/sampler :pure})
    => :pure
    (cp.sampler/derive-sampler-type
      {::cp.sampler/sampler ::cp.sampler/pure})
    => ::cp.sampler/pure
    (cp.sampler/derive-sampler-type
      {::cp.sampler/sampler [:foobar 1]})
    => [:foobar 1]))

(specification "flatten-samples"
  (let [->S cp.sampler/->samples]
    (assertions
      (cp.sampler/flatten-samples
        #{(->S #{:a :b}) :c (->S #{:d :e}) [:f :g]})
      => #{:a :b :c :d :e [:f :g]})))

(specification "get-args"
  (let [env (cp.art/build-env)]
    (assertions
      (cp.sampler/get-args env {})
      =throws=> #"Failed to get samples"
      (cp.sampler/get-args env {::cp.art/samples #{}})
      =throws=> #"Failed to get samples"
      (cp.sampler/get-args env {::cp.art/samples #{123}})
      => #{123}
      (cp.sampler/get-args env {::cp.art/samples #{}
                                 ::cp.art/fn-ref  identity})
      => [identity]
      (cp.sampler/get-args env {::cp.art/samples #{123}
                                 ::cp.art/fn-ref  identity})
      => #{123})))

(specification "samples-gen"
  (let [env (cp.art/build-env)]
    (assertions
      (gen/sample (cp.sampler/args-gen env [#{:a :b :c}]))
      =check=> (_/every?*
                 (tc/of-length?* 1)
                 (_/seq-matches?*
                   [(_/is?* #{:a :b :c})]))
      (gen/sample (cp.sampler/args-gen env [#{:a :b :c}
                                             #{1 2 3}]))
      =check=> (_/every?*
                 (tc/of-length?* 2)
                 (_/seq-matches?*
                   [(_/is?* #{:a :b :c})
                    (_/is?* #{1 2 3})]))
      (gen/sample (cp.sampler/args-gen env [[identity]
                                             #{:a :b :c}]))
      =check=> (_/every?*
                 (tc/of-length?* 2)
                 (_/seq-matches?*
                   [(_/equals?* identity)
                    (_/is?* #{:a :b :c})])))))

(specification "params-gen" :WIP
  (let [env (cp.art/build-env)]
    (when-mocking
      (cp.sampler/get-gspec _ _) => {::cp.art/sampler     ::cp.sampler/pure
                                     ::cp.art/return-spec int?}
      (assertions
        (gen/sample (cp.sampler/params-gen env {::cp.art/fn-ref +} []))
        =check=> (_/every?*
                   (_/embeds?*
                     {:fn-ref           (_/equals?* +)
                      :params           nil
                      :argtypes         []
                      :return-sample-fn (checker [f] ((_/is?* int?) (f)))}))))))

(specification "propagate-samples!"
  (let [env (cp.art/build-env)]
    (component "default"
      (assertions
        (cp.sampler/propagate-samples! env nil
          {:return-sample-fn (constantly ::test-sample)})
        => ::test-sample))
    (component "pure"
      (let [test-fn-type {::cp.art/fn-ref +
                          ::cp.art/arities
                          {:n {::cp.art/arglist '[& nums]
                               ::cp.art/gspec
                               {::cp.art/return-spec number?
                                ::cp.art/return-type "number?"}}}}]
        (assertions
          (cp.sampler/propagate-samples! env ::cp.sampler/pure
            {:fn-ref   str :args ["pure" \: "test"]
             :argtypes [{} {} {}]})
          => "pure:test"
          (cp.sampler/propagate-samples! env ::cp.sampler/pure
            {:fn-ref   apply :args [+ [1/3 1/5 1/7]]
             :argtypes [test-fn-type {}]})
          =check=> (_/all* (_/is?* number?)
                     (_/is?* #(not= 71/105 %)))
          (cp.sampler/propagate-samples! env ::cp.sampler/pure
            {:fn-ref   apply :args [+ [1/3 1/5 1/7]]
             :argtypes [(assoc-in test-fn-type
                          [::cp.art/arities :n ::cp.art/gspec ::cp.art/sampler]
                          ::cp.sampler/pure)
                        {}]})
          => 71/105)))
    (component "merge-arg"
      (assertions
        (cp.sampler/propagate-samples! env [::cp.sampler/merge-arg 1]
          {:args             [:db {:person/name "john"}]
           :params           1
           :return-sample-fn (constantly {:person/full-name "john doe"})})
        => #:person{:name "john" :full-name "john doe"}))
    (component "map-like"
      (let [test-fn-type {::cp.art/fn-ref +
                          ::cp.art/arities
                                           {:n {::cp.art/arglist '[& nums]
                                                ::cp.art/gspec
                                                                  {::cp.art/return-spec number?
                                                                   ::cp.art/return-type "number?"}}}}]
        (assertions
          (cp.sampler/map-like-args env
            [{::cp.art/samples #{[1 2 3]}}
             {::cp.art/samples #{[4 5 6]}}])
          => [[1 4] [2 5] [3 6]]
          (cp.sampler/map-like-args env
            [{::cp.art/samples #{[1 2]}}
             {::cp.art/samples #{[4 5 6]}}])
          => [[1 4] [2 5]]
          (cp.sampler/map-like-args env
            [{::cp.art/samples #{1 2}}
             {::cp.art/samples #{[3 4]}}])
          =throws=> #"expects all sequence arguments to be sequences"
          (cp.sampler/propagate-samples! env ::cp.sampler/map-like
            {:args             [+ [1 2 3] [4 5 6]]
             :argtypes         [(assoc-in test-fn-type
                                  [::cp.art/arities :n ::cp.art/gspec ::cp.art/metadata]
                                  {::cp.sampler/sampler :pure})
                                {::cp.art/samples #{[1 2 3]}}
                                {::cp.art/samples #{[4 5 6]}}]
             :gspec            {::cp.art/sampler ::cp.sampler/pure}
             :return-sample-fn (constantly [:error])})
          => #{[5 7 9]}
          "if there is no sampler, returns coll of function's return-spec"
          (cp.sampler/propagate-samples! env ::cp.sampler/map-like
            {:args             [+ []]
             :argtypes         [test-fn-type {}]
             :gspec            {::cp.art/sampler ::cp.sampler/pure}
             :return-sample-fn (constantly [:error])})
          =check=> (_/all* (_/is?* seq)
                     (_/every?* (_/is?* number?))))))))

(specification "random-samples-from"
  (let [env (cp.art/build-env)]
    (assertions
      (cp.sampler/random-samples-from env
        {::cp.art/samples #{1 2 3}}
        {::cp.art/samples #{:a :b :c}})
      =check=> (_/every?* (_/is?* #{1 2 3 :a :b :c}))
      "handles unknown expressions"
      (cp.sampler/random-samples-from env
        (cp.ana.disp/unknown-expr env ::UNK))
      => #{}
      (cp.sampler/random-samples-from env
        {::cp.art/samples #{4 5 6}}
        (cp.ana.disp/unknown-expr env ::UNK))
      =check=> (tc/subset?* #{4 5 6})
      "handles empty or nil samples"
      (cp.sampler/random-samples-from env
        {::cp.art/samples #{}})
      => #{}
      (cp.sampler/random-samples-from env
        {})
      => #{}
      )))

(specification "random-samples-from-each"
  (let [env (tf/test-env)]
    (assertions
      (cp.sampler/random-samples-from-each env
        [{::cp.art/samples #{1 2 3}}
         {::cp.art/samples #{:a :b :c}}])
      =check=> (_/every?*
                 (_/seq-matches?*
                   [(_/is?* number?)
                    (_/is?* keyword?)]))
      "handles unknown expressions"
      (cp.sampler/random-samples-from-each env
        [(cp.ana.disp/unknown-expr env ::UNK)])
      => #{}
      "handles empty or nil samples"
      (cp.sampler/random-samples-from-each env
        [{}])
      => #{}
      (cp.sampler/random-samples-from-each env
        [{::cp.art/samples #{}}])
      => #{}
      ;; TODO: not sure is 100% correct, should it have a nil at the end of each vector?
      (cp.sampler/random-samples-from-each env
        [{::cp.art/samples #{1 2 3}}
         {::cp.art/samples #{}}])
      =check=> (tc/subset?* #{[1] [2] [3]}))))
