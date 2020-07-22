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
     (str y "hello" ::a))))

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

