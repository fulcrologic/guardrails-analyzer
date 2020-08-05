(ns com.fulcrologic.guardrails-pro.interpreter-spec
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.interpreter :as i #?@(:cljs [:refer [Primitive ASymbol Call Unknown]])]
    [taoensso.timbre :as log]
    [fulcro-spec.core :refer [specification assertions behavior component]]))

(def empty-env {::a/registry {}})

(specification "recognize"
  (let [s (i/recognize empty-env 'a)
        c (i/recognize empty-env '(f x y))]
    (assertions
      "Symbols"
      (::i/type s) => :symbol
      (::i/sym s) => 'a)
    (assertions
      "Function calls"
      (::i/type c) => :call
      (::i/form c) => '(f x y))
    (assertions
      "Strings"
      (::i/type (i/recognize empty-env "")) => :primitive
      "Ints"
      (::i/type (i/recognize empty-env 1)) => :primitive
      "Floats"
      (::i/type (i/recognize empty-env 3.6)) => :primitive
      "Booleans"
      (::i/type (i/recognize empty-env true)) => :primitive)))

(specification "Checking primitives"
  (let [int-type    (i/typecheck empty-env (i/->Primitive 3))
        double-type (i/typecheck empty-env (i/->Primitive 3.8))]
    (assertions
      "Includes the spec"
      (::a/spec int-type) => int?
      (::a/spec double-type) => double?
      "Includes samples"
      (and (seq (::a/samples int-type)) (every? int? (::a/samples int-type))) => true
      (and (seq (::a/samples double-type)) (every? double? (::a/samples double-type))) => true)))

(specification "Checking a symbol"
  (let [symbol-type  (i/typecheck (merge empty-env
                                    {::a/local-symbols {'x {::a/samples [-1 0 1]
                                                            ::a/spec    int?}}}) (i/->ASymbol 'x))
        extern-type  (i/typecheck (merge empty-env
                                    {::a/extern-symbols {'x {::a/extern-name 'x
                                                             ::a/value       42}}})
                       (i/->ASymbol 'x))
        unbound-type (i/typecheck empty-env (i/->ASymbol 'x))]
    (assertions
      "Finds the type in env"
      (identical? int? (::a/spec symbol-type)) => true
      "Includes samples for external symbols that have no more details"
      (::a/samples extern-type) => [42]
      "Returns Unknown when not in env"
      unbound-type => {})))

(let [y 2]
  (grp/>defn f
    ([x y]
     ^::a/pure? [int? => int?]
     x)
    ([x]
     ^{::a/typecalc {::a/dispatch :adds-person-name}} [map? => map?]
     y)))

(comment
  (a/function-detail `f))

(specification "bind-argument-types"
  (let [env           (i/build-env)
        f-description (get-in env [::a/registry `f ::a/arities 1])
        {::a/keys [local-symbols]} (i/bind-argument-types env f-description)
        binding       (get local-symbols 'x)]
    (assertions
      "Binds local symbols to the type and samples of the argument list"
      (map? binding) => true
      "Has a type descriptions"
      (::a/type binding) => "int?"
      "whose type is the spec"
      (::a/spec binding) => int?
      "and includes samples that conform to the type"
      (empty? (::a/samples binding)) => false
      (every? #(s/valid? int? %) (::a/samples binding)) => true)))
