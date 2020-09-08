(ns com.fulcrologic.guardrails-pro.parser-spec
  (:require
    [com.fulcrologic.guardrails-pro.parser :as grp.parser]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.forms :as forms]
    [fulcro-spec.core :refer [specification behavior assertions provided when-mocking when-mocking! => =fn=> =throws=>]]))

(specification "function-name"
  (assertions
    "if the name is required, it must be a simple symbol"
    (-> (grp.parser/init-parser-state '[123])
      grp.parser/function-name)
    =throws=> #"is not a simple symbol"
    (-> (grp.parser/init-parser-state '[foo])
      grp.parser/function-name)
    => #::grp.parser{:result {} :args nil :fn-meta nil :opts {}}
    "the name can be optional"
    (-> (grp.parser/init-parser-state '[123] {:optional-fn-name? true})
      (grp.parser/function-name))
    => #::grp.parser{:result {} :args [123] :opts {:optional-fn-name? true}}
    "stores the var's metadata in state"
    (-> (grp.parser/init-parser-state '[^:test/meta foo])
      grp.parser/function-name
      ::grp.parser/fn-meta)
    => {:test/meta true}))

(specification "var-name"
  (assertions
    "must receive a fully qualified symbol"
    (-> (grp.parser/init-parser-state ['bar])
      grp.parser/var-name)
    =throws=> #"bar is not fully qualified"
    "stores the var's metadata in state"
    (-> (grp.parser/init-parser-state '[^:test/meta foo/bar])
      grp.parser/var-name
      ::grp.parser/fn-meta)
    => {:test/meta true}))

(specification "arg-specs" :unit
  (assertions
    (grp.parser/arg-specs
      (grp.parser/init-parser-state '[int? int? => foo]))
    => #::grp.parser{:args '(=> foo)
                     :opts {}
                     :result #::grp.art{:arg-types ["int?" "int?"]
                                        :arg-specs '[int? int?]}}
    (grp.parser/arg-specs
      (grp.parser/init-parser-state '[=> foo]))
    => #::grp.parser{:args '[=> foo] :result {} :opts {}}))

(specification "arg-predicates" :unit
  (assertions
    (grp.parser/arg-predicates
      (grp.parser/init-parser-state '[| #(even? a) => foo])
      '[a])
    => #::grp.parser{:args '(=> foo)
                     :opts {}
                     :result #::grp.art{:arg-predicates '[(fn* [a] (even? a))]}}))

(specification "return-type" :unit
  (assertions
    (grp.parser/return-type
      (grp.parser/init-parser-state '[=> foo?]))
    => #::grp.parser{:args nil
                     :opts {}
                     :result #::grp.art{:return-spec 'foo?
                                        :return-type "foo?"}}))


(specification "gspec-metadata" :unit
  (assertions
    "stores the gspec metadata under ::grp.art/metadata"
    (-> (grp.parser/init-parser-state
          (with-meta '[int? => pos? <- gen/pos]
            {:pure? true}))
      (grp.parser/gspec-metadata)
      (get-in [::grp.parser/result ::grp.art/metadata]))
    => {:pure? true}
    "does not put nil under metadata (if no metadata)"
    (-> (grp.parser/init-parser-state
          '[int? => pos? <- gen/pos])
      (grp.parser/gspec-metadata)
      (get-in [::grp.parser/result ::grp.art/metadata] ::not-found))
    => ::not-found))

(specification "gspec-parser" :unit
  (assertions
    (grp.parser/gspec-parser
      (grp.parser/init-parser-state '[int? => pos? <- gen/pos])
      '[a])
    => #::grp.parser{:args nil
                     :opts {}
                     :result #::grp.art{:arg-types   ["int?"]
                                        :arg-specs   '[int?]
                                        :return-spec 'pos?
                                        :return-type "pos?"
                                        :generator   'gen/pos}}))

(specification "lambda:env->fn"
  (assertions
    ;(grp.parser/lambda:env->fn:impl '[a] '(fn [x] [int? => int?] (inc a))) => :for-debugging
    (((grp.parser/lambda:env->fn [a]
        (fn [x] [int? => int?] (inc a)))
      {'a 42}) 0)
    => 43))

(specification "parse-body-for-lambdas"
  (when-mocking!
    (gensym _) => 'MOCK_LAMBDA
    (assertions
      "returns an empty map if there are no >fn's"
      (-> (grp.parser/init-parser-state '[])
        (grp.parser/parse-body-for-lambdas)
        (get-in [::grp.parser/result ::grp.art/lambdas]))
      => {}
      "names lambda with gensym"
      (-> (grp.parser/init-parser-state '[(>fn [x] [int? => int?] (inc x))])
        (grp.parser/parse-body-for-lambdas)
        (get-in [::grp.parser/result ::grp.art/lambdas])
        ffirst)
      => '(quote MOCK_LAMBDA)
      "if lambda name += gensym"
      (-> (grp.parser/init-parser-state '[(>fn foo [x] [int? => int?] (inc x))])
        (grp.parser/parse-body-for-lambdas)
        (get-in [::grp.parser/result ::grp.art/lambdas])
        ffirst)
      => '(quote foo$$MOCK_LAMBDA)))
  (when-mocking!
    (grp.parser/parse-fn _) => {::grp.art/arities ::MOCK_ARITIES}
    (assertions
      "parses >fn into arities, etc"
      (-> (grp.parser/init-parser-state '[(>fn foo [x] [int? => int?] (inc x))])
        (grp.parser/parse-body-for-lambdas)
        (get-in [::grp.parser/result ::grp.art/lambdas])
        first second ::grp.art/arities)
      => ::MOCK_ARITIES))
  (assertions
    "creates an env->fn"
    (-> (grp.parser/init-parser-state '[(>fn foo [x] [int? => int?] (inc x))])
      (grp.parser/parse-body-for-lambdas)
      (get-in [::grp.parser/result ::grp.art/lambdas])
      first second ::grp.art/env->fn first)
    => `fn))

(specification "body-arity"
  (assertions
    "returns :n if the arglist contains an ampersand <&>"
    (grp.parser/body-arity '[& _]) => :n
    (grp.parser/body-arity '[x & _]) => :n
    (grp.parser/body-arity '[x [y] & _]) => :n
    "does not parse nested ampersands"
    (grp.parser/body-arity '[x [y & _]]) => 2
    "returns the count of the arglist otherwise"
    (grp.parser/body-arity (range 3)) => 3))

(specification "single-arity"
  (provided "parses a gspec with gspec-parser"
    (grp.parser/gspec-parser & _) => {::grp.parser/result ::MOCK_GSPEC}
    (assertions
      (-> (grp.parser/init-parser-state '[[:stub/arglist] :stub/gspec :stub/body])
        (grp.parser/single-arity)
        ::grp.parser/result
        ::grp.art/arities (get 1)
        ::grp.art/gspec)
      => ::MOCK_GSPEC)
    (when-mocking!
      (forms/form-expression x) => [::with-meta x]
      (assertions
        "returns an arglist with metadata preserved for runtime"
        (-> (grp.parser/init-parser-state [[:stub/arglist] :stub/gspec :stub/body])
          (grp.parser/single-arity)
          ::grp.parser/result
          ::grp.art/arities (get 1)
          ::grp.art/arglist)
        => [::with-meta [:stub/arglist]]
        "returns the body with metadata preserved for runtime"
        (-> (grp.parser/init-parser-state [[:stub/arglist] :stub/gspec :stub/body])
          (grp.parser/single-arity)
          ::grp.parser/result
          ::grp.art/arities (get 1)
          ::grp.art/body)
        => [::with-meta [:stub/body]]
        "stores the raw body in the arity's metadata"
        (-> (grp.parser/init-parser-state [[:stub/arglist] :stub/gspec :stub/body])
          (grp.parser/single-arity)
          ::grp.parser/result
          ::grp.art/arities (get 1)
          meta ::grp.art/raw-body)
        => '(quote [:stub/body])))))

(specification "multiple-aritites"
  (provided "calls single-arity to parse each arity"
    (grp.parser/single-arity x) => [::MOCK_ARITY (::grp.parser/args x)]
    (assertions
      (-> (grp.parser/init-parser-state '[(:stub/arity)])
        (grp.parser/multiple-arities))
      => [::MOCK_ARITY '(:stub/arity)]
      "expects all args to be `arity-body?`'s"
      (-> (grp.parser/init-parser-state [:stub/errant-arity])
        (grp.parser/multiple-arities))
      =throws=> #"multi-arity function body expected")))

(specification "such-that"
  (assertions
    "only parses if the lookahead satisfies `such-that?`"
    (-> (grp.parser/init-parser-state [:foo even?])
      grp.parser/such-that)
    => #::grp.parser{:result {}
                     :args [:foo even?]
                     :opts {}}
    "consumes one or more return predicates"
    (-> (grp.parser/init-parser-state ['| number? even?])
      grp.parser/such-that)
    => #::grp.parser{:result #::grp.art{:return-predicates [number? even?]}
                     :args nil
                     :opts {}}
    "consumes until end of args or a `gen?`"
    (-> (grp.parser/init-parser-state ['| even? '<- odd?])
      grp.parser/such-that)
    => #::grp.parser{:result #::grp.art{:return-predicates [even?]}
                     :args ['<- odd?]
                     :opts {}}))

(specification "generator"
  (assertions
    "only parses if the lookahead satisfies `gen?`"
    (-> (grp.parser/init-parser-state [:foo even?])
      grp.parser/generator)
    => #::grp.parser{:result {} :args [:foo even?] :opts {}}
    "returns the generator"
    (-> (grp.parser/init-parser-state ['<- even?])
      grp.parser/generator)
    => #::grp.parser{:result #::grp.art{:generator even?} :args nil :opts {}}))

(specification "function-content"
  (behavior "dispatches based on `arity-body?` to"
    (provided "single-arity"
      (grp.parser/single-arity _) => ::MOCK_SINGLE
      (assertions
        (-> (grp.parser/init-parser-state '[:stub/arity])
          (grp.parser/function-content))
        => ::MOCK_SINGLE))
    (provided "or multiple-arities"
      (grp.parser/multiple-arities _) => ::MOCK_MULTIPLE
      (assertions
        (-> (grp.parser/init-parser-state '[(:stub/arglist)])
          (grp.parser/function-content))
        => ::MOCK_MULTIPLE))))

(specification "parse-fdef" :unit
  (assertions
    "parses var-name"
    (-> (grp.parser/parse-fdef '[^:test/pure foo/bar [] [=> int?]])
      (get-in [::grp.art/arities 0 ::grp.art/gspec ::grp.art/metadata]))
    => {:test/pure true}
    "should not contain function bodies"
    (-> (grp.parser/parse-fdef '[foo/bar [x] [int? => int?] :errant/body])
      (get-in [::grp.art/arities 1 ::grp.art/body] ::not-found))
    =throws=> #"function body not expected"
    (-> (grp.parser/parse-fdef '[foo/bar [x] [int? => int?]])
      (get-in [::grp.art/arities 1 ::grp.art/body] ::not-found))
    => ::not-found))

(specification "parse-fspec" :unit
  (assertions
    "should not contain a function name"
    (grp.parser/parse-fspec '[foo])
    =throws=> #"not contain a function name"
    "should not contain function bodies"
    (-> (grp.parser/parse-fspec '[[x] [int? => int?] :errant/body])
      (get-in [1 ::grp.art/body] ::not-found))
    =throws=> #"function body not expected"
    (-> (grp.parser/parse-fspec '[[x] [int? => int?]])
      (get-in [1 ::grp.art/body] ::not-found))
    => ::not-found))
