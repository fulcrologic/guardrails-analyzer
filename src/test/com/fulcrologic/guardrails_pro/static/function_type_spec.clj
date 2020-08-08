(ns com.fulcrologic.guardrails-pro.static.function-type-spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.static.function-type :refer [calculate-function-type]]
    [com.fulcrologic.guardrails.core :as gr :refer [=> | <-]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [fulcro-spec.core :refer [specification assertions behavior =fn=>]]))

(grp/>defn f [x]
  [int? => int?]
  (inc x))

(grp/>defn g [x]
  [int? => int? <- (gen/fmap #(- % 1000) (gen/int))]
  (inc x))

(grp/>defn h [x]
  [int? | #(pos? x) => int?]
  (inc x))

(grp/>defn n [x]
  [int? => int? | #(neg? %)]
  (if (pos? x) (- x) x))

(grp/>defn p [x]
  ^::a/pure? [int? => int?]
  (if (neg? x) (- x) x))

(gr/>defn type-description
  "Generate a type description for a given spec"
  [name expr spec samples]
  [string? any? any? any? => ::a/type-description]
  {::a/spec                spec
   ::a/type                name
   ::a/samples             samples
   ::a/original-expression expr})

(specification "calculate-function-type (non-special)"
  (behavior "Uses the function's return spec to generate the type description"
    (let [arg-type-description (type-description "int?" 42 int? #{42})
          env                  (a/build-env)
          {::a/keys [spec type samples]} (calculate-function-type env `f [arg-type-description])]
      (assertions
        "Has the return type described in the function"
        spec => int?
        type => "int?"
        "Has samples generated from that spec"
        (boolean (seq samples)) => true
        samples =fn=> #(every? int? %))))
  (behavior "If spec'ed to have a custom generator"
    (let [arg-type-description (type-description "int?" 42 int? #{42})
          env                  (a/build-env)
          {::a/keys [samples]} (calculate-function-type env `g [arg-type-description])]
      (assertions
        "Has samples generated from the custom generator"
        (boolean (seq samples)) => true
        samples =fn=> #(every? neg-int? %))))
  (behavior "If the return type gspec as a such that clause"
    (behavior "It is used to restrict the generated samples"
      (let [arg-type-description (type-description "int?" 42 int? #{42})
            env                  (a/build-env)
            {::a/keys [samples]} (calculate-function-type env `n [arg-type-description])]
        (assertions
          (boolean (seq samples)) => true
          samples =fn=> #(every? neg-int? %)))))
  (behavior "Verifies the argtypes based on the arglist specs"
    (let [arg-type-description (type-description "int?" 'x int? #{"3" 22})
          env                  (a/build-env)
          {::a/keys [errors]} (calculate-function-type env `h [arg-type-description])
          error (first errors)]
      (assertions
        (::a/original-expression error) => 'x
        (::a/actual error) => "3"
        (::a/expected error) => `int?)))
  (behavior "If spec'ed to have argument predicates"
    (behavior "Returns any errors"
      (let [arg-type-description (type-description "int?" 'x int? #{3 -42})
            env                  (a/build-env)
            {::a/keys [errors]} (calculate-function-type env `h [arg-type-description])
            error (first errors)]
        (assertions
          (::a/original-expression error) => 'x
          (::a/actual error) => -42
          ;;TODO: should be more explanatory than just a fn ref
          (::a/expected error) =fn=> fn?)))))

(specification "calculate-function-type (pure)"
  (behavior "Uses the function itself to generate type information"
    (let [arg-type-description (type-description "int?" 42 int? #{42 -32})
          env                  (a/build-env)
          {::a/keys [samples]} (calculate-function-type env `p [arg-type-description])]
      (assertions
        (boolean (seq samples)) => true
        samples =fn=> #(every? pos? %))))
  (behavior "Still checks its arguments based on their specs"
    (let [arg-type-description (type-description "int?" 'x int? #{"3" 22})
          env                  (a/build-env)
          {::a/keys [errors]} (calculate-function-type env `p [arg-type-description])
          error (first errors)]
      (assertions
        (::a/original-expression error) => 'x
        (::a/actual error) => "3"
        (::a/expected error) => `int?))))
