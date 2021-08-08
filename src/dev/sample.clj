(ns sample
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn => | ?]]))

(>defn f [x]
  [int? => int?]
  :x/boo
  "hello world")
