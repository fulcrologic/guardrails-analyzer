(ns com.fulcrologic.guardrails-pro.interpreter-spec
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.interpreter :as interpreter #?@(:cljs [:refer [Primitive ASymbol Call Unknown]])]
    [taoensso.timbre :as log]
    [fulcro-spec.core :refer [specification assertions behavior component]])
  #?(:clj
     (:import
       [com.fulcrologic.guardrails_pro.interpreter Primitive Call ASymbol])))

(specification "recognize"
  (let [s (interpreter/recognize {} 'a)
        c (interpreter/recognize {} '(f x y))]
    (assertions
      "Symbols"
      (type s) => ASymbol
      (.-sym s) => 'a)
    (assertions
      "Function calls"
      (type c) => Call
      (.-form c) => '(f x y))
    (assertions
      "Strings"
      (type (interpreter/recognize {} "")) => Primitive
      "Ints"
      (type (interpreter/recognize {} 1)) => Primitive
      "Floats"
      (type (interpreter/recognize {} 3.6)) => Primitive
      "Booleans"
      (type (interpreter/recognize {} true)) => Primitive)))

(specification "Checking primitives" :focus
  (let [int-type    (interpreter/typecheck {} (Primitive. 3))
        double-type (interpreter/typecheck {} (Primitive. 3.8))]
    (assertions
      "Includes the spec"
      (::a/spec int-type) => int?
      (::a/spec double-type) => double?
      "Includes samples"
      (and (seq (::interpreter/samples int-type)) (every? int? (::interpreter/samples int-type))) => true
      (and (seq (::interpreter/samples double-type)) (every? double? (::interpreter/samples double-type))) => true)))

(specification "Checking a symbol"
  (let [symbol-type  (interpreter/typecheck {::interpreter/local-symbols {'x {::interpreter/samples [-1 0 1]
                                                                              ::interpreter/spec    int?}}} (ASymbol. 'x))
        extern-type  (interpreter/typecheck {::interpreter/extern-symbols {'x {:name  'x
                                                                               :value 42}}}
                       (ASymbol. 'x))
        unbound-type (interpreter/typecheck {} (ASymbol. 'x))]
    (assertions
      "Finds the type in env"
      (identical? int? (::interpreter/spec symbol-type)) => true
      "Includes samples for external symbols that have no more details"
      (::interpreter/samples extern-type) => [42]
      "Returns Unknown when not in env"
      unbound-type => {})))

(let [y 2]
  (grp/>defn f
    ([x y]
     [int? => int?]
     (str y "hello" ::a))
    ([x]
     [int? => int?]
     (str y "hello" ::a))
    ))

(specification "bind-argument-types"
  (let [env           (interpreter/build-env)
        f-description (get-in env [::interpreter/registry `f ::a/arities 1])
        {::interpreter/keys [local-symbols]} (interpreter/bind-argument-types env f-description)
        binding       (get local-symbols 'x)]
    (assertions
      "Binds local symbols to the type and samples of the argument list"
      (map? binding) => true
      "whose type is the spec"
      (:type binding) => int?
      "and includes samples that conform to the type"
      (empty? (:samples binding)) => false
      (every? #(s/valid? int? %) (:samples binding)) => true)))

(comment
  @a/memory
  (let [env (interpreter/build-env @a/memory)]
    (interpreter/check! env `f)
    (::interpreter/errors env)))
#_(specification "Checking a function argument"
    #_(let [env (interpreter/build-env {'f {:argument-specs {1 [string?]}}
                                        'g {:argument-specs {1 [int?]}
                                            :return-type    string?}})
            env (interpreter/bind-type env 'x int?)
            typ (interpreter/check-argument! env (Call. '(f (g x))) 0)]
        (assertions
          "Will type-check sub-expressions"
          (identical? string? typ) => true))
    (let [env (interpreter/build-env {'f {:file  "some.cljc"
                                          :line  33
                                          :arity {1 {:argument-types [string?]
                                                     :return-type    int?}}}})
          env (interpreter/bind-type env 'x int?)
          typ (interpreter/check-argument! env (Call. '(f x)) 0)]
      (assertions
        "Returns the type of the argument when it type-checks"
        (identical? int? typ) => true)))

#_(specification "Checking a general function expression"
    (let [env (interpreter/build-env {'f {:file  "some.cljc"
                                          :line  33
                                          :arity {1 {:argument-types [string?]
                                                     :return-type    int?}}}})
          typ (interpreter/typecheck env (Call. '(f x)))]
      (assertions
        "Always indicates the return type of the function"
        (identical? int? typ) => true
        "Returns ::Unknown for unregistered functions"
        (interpreter/typecheck env (Call. '(g x))) => ::interpreter/Unknown))
    (behavior "Adds an error to the env if there is a provable problem with the arguments"
      (component "Primitive argument expression"
        (let [env (-> (interpreter/build-env {'f {:arity {1 {:argument-types [string?]
                                                             :return-type    int?}}}})
                    (interpreter/parsing-context "some.cljc" 33))]

          (interpreter/typecheck env (Call. '(f 23)))

          (assertions
            "Gives an accurate representation of the mistake"
            (first (interpreter/error-messages env)) => "some.cljc:33: Argument 0 of (f 23) should have type string?.\nHowever, the expression ->23<- has type int?")))
      (component "A symbolic argument with a known type"
        (let [env         (-> (interpreter/build-env {'f {:arity {1 {:argument-types [string?]
                                                                     :return-type    int?}}}})
                            (interpreter/bind-type 'a int?)
                            (interpreter/parsing-context "some.cljc" 33))

              result-type (interpreter/typecheck env (Call. '(f a)))

              msg         (subs (first (interpreter/error-messages env)) 0 140)]
          (assertions
            "Returns the correct type of `f`."
            (identical? int? result-type) => true
            "Gives an example of how it could fail."
            msg => "some.cljc:33: Argument 0 of (f a) should have type string?.\nHowever, the expression ->a<- could result in a value with an invalid type (such")))
      (component "An argument that is a nested function call with a known return type"
        (let [env         (-> (interpreter/build-env {'f {:arity {1 {:argument-types [string?]
                                                                     :return-type    int?}}}
                                                      'g {:arity {0 {:argument-types []
                                                                     :return-type    pos-int?}}}})
                            (interpreter/bind-type 'a int?)
                            (interpreter/parsing-context "some.cljc" 33))

              result-type (interpreter/typecheck env (Call. '(f (g))))

              msg         (subs (first (interpreter/error-messages env)) 0 148)]
          (assertions
            "Gives an example of how it could fail."
            msg => "some.cljc:33: Argument 0 of (f (g)) should have type string?.\nHowever, the expression ->(g)<- could result in a value with an invalid type (such as:"
            "Indicates the correct return type of the checked function (to prevent cascading fails)"
            (identical? int? result-type) => true)))))

#_(specification "Let expressions" :focus
    (let [env         (-> (interpreter/build-env {`fmt {:arity {2 {:argument-types [string? any?]
                                                                   :return-type    string?}}}
                                                  `add {:arity {2 {:argument-types [number? number?]
                                                                   :return-type    number?}}}})
                        (interpreter/parsing-context "file.cljc" 11))
          expr        `(let [~'a 1
                             ~'x "hello"
                             ~'b (add ~'a ~'x)
                             ~'c (fmt ~'x ~'b)]
                         (bam! -45)
                         (fmt "hello" ~'c))
          result-type (interpreter/typecheck env (interpreter/recognize env expr))
          msgs        (interpreter/error-messages env)]
      (assertions
        "Result in the type of the last expression"
        (identical? string? result-type) => true
        "Type check each binding and body expression, adding error messages when problems are found."
        (count msgs) => 1
        (subs (first msgs) 0 140) => "file.cljc:11: Argument 1 of (com.fulcrologic.guardrails.static.checker-spec/add a x) should have type number?.\nHowever, the expression ->x<-")))

