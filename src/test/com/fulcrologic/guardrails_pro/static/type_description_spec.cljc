(ns com.fulcrologic.guardrails-pro.static.type-description-spec
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.static.function-type :as types]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.interpreter :as i #?@(:cljs [:refer [Primitive ASymbol Call Unknown]])]
    [taoensso.timbre :as log]
    [fulcro-spec.core :refer [specification assertions behavior component]]))

(grp/>defn f [x]
  [int? => int?]
  42)

(grp/>defn g [x]
  [string? => string?]
  "hello")

(specification "Type description"
  (component "Of a primitive number"
    (let [env (a/build-env)
          {::a/keys [spec type samples original-expression]} (analyze/analyze env 2)]
      (assertions
        "has a spec"
        spec => int?
        "has a type string"
        type => "int?"
        "has sample that are the original value"
        samples => #{2}
        "includes the original expression"
        original-expression => 2)))
  (component "Of a simple function call"
    (let [env (a/build-env)
          {::a/keys [spec type samples original-expression]} (types/get-type-description env `(f 0))]
      (assertions
        "has a spec"
        spec => int?
        "has a type string"
        type => "int?"
        "has samples"
        (boolean (seq samples)) => true
        (every? int? samples) => true
        "includes the original expression"
        original-expression => `(f 0))))
  (component "Of a let expression"
    (let [env (a/build-env)
          {::a/keys [spec type samples original-expression errors]} (types/get-type-description env `(let [a 3] (f a)))]
      (assertions
        "has a spec"
        spec => int?
        "has a type string"
        type => "int?"
        "has samples"
        (boolean (seq samples)) => true
        (every? int? samples) => true
        "includes the original expression"
        original-expression => `(let [a 3] (f 3)))))
  (component "Of a f w/HOF"
    (let [env (a/build-env)
          {::a/keys [spec type samples original-expression errors]} (types/get-type-description env `(map #(+ 1 %) [1 2 3]))]
      (assertions
        "has samples"
        (boolean (seq samples)) => true
        (every? int? samples) => true)))
  (component "Of a nested let expression"
    (let [env (a/build-env)
          {::a/keys [spec type samples original-expression errors]} (types/get-type-description env `(g (let [a 3] (f a))))]
      (assertions
        "has a spec"
        spec => int?
        "has a type string"
        type => "int?"
        "has samples"
        (boolean (seq samples)) => true
        (every? int? samples) => true)))
  (component "Of a nested function call"
    (let [env (a/build-env)
          {::a/keys [spec type samples original-expression errors]} (types/get-type-description env `(f (g 0)))
          [error1 error2] errors]
      (assertions
        "has a spec"
        spec => int?
        "has a type string"
        type => "int?"
        "has samples"
        (boolean (seq samples)) => true
        (every? int? samples) => true
        "includes the original expression"
        original-expression => `(f 0)
        "includes errors about type mismatches"
        (::a/message error1) => "Expected string? and got 0"
        (-> error1 ::a/expected ::a/type) => "string?"
        (-> error1 ::a/expected ::a/found) => "int?"))))