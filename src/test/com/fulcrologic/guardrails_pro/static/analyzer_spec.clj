(ns com.fulcrologic.guardrails-pro.static.analyzer-spec
  (:require
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.static.analyzer :as src]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [clojure.java.io :as io]
    [fulcro-spec.core :refer [specification assertions behavior =fn=>]]))

(grp/>defn f [x]
  [int? => int?]
  (inc x))

(specification "analyze"
  (let [env (a/build-env)
        td  (src/analyze env `(let [~'a :a-kw] (f ~'a)))]
    (assertions
      td => :WIP
      )))
