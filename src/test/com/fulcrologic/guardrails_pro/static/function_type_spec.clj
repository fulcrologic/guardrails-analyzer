(ns com.fulcrologic.guardrails-pro.static.function-type-spec
  (:require
    [com.fulcrologic.guardrails-pro.core]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.static.function-type :refer [calculate-function-type]]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [fulcro-spec.core :refer [specification assertions behavior component]]))

(grp/>defn f [x]
  [int? => int?])
(comment
  @a/memory)

(gr/>defn type-description
  "Generate a type description for a given spec"
  ([name expr spec samples]
   [string? any? any? any? => ::a/type-description]
   {::a/spec                spec
    ::a/type                name
    ::a/samples             samples
    ::a/original-expression expr}))

(specification "calculate-function-type (non-special)"
  (behavior "Uses the function's return spec to generate the type description"
    (let [arg-type-description (type-description "int?" 42 int? #{42})
          env                  (a/build-env)
          {::a/keys [spec type samples] :as return-type} (calculate-function-type env `f [arg-type-description])]
      (assertions
        "Has the return type described in the function"
        spec => int?
        type => "int?"
        ;"Has samples generated from that spec"
        ;(seq samples) => true
        #_#_#_(every? int? samples) => true))))