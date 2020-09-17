(ns com.fulcrologic.guardrails-pro.core-spec
  (:require
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [com.fulcrologic.guardrails-pro.analysis.interpreter :as grp.int]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [fulcro-spec.core :refer [specification assertions when-mocking!]]))
