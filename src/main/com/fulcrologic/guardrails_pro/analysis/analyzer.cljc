(ns com.fulcrologic.guardrails-pro.analysis.analyzer
  (:require
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :refer [analyze-mm -analyze!]]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.literals]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.macros]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.hofs]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn =>]]
    [taoensso.timbre :as log]))

(>defn analyze!
  [env sexpr]
  [::grp.art/env any? => ::grp.art/type-description]
  (-analyze! env sexpr))

(defmethod analyze-mm :unknown [_ sexpr]
  (log/warn "Could not analyze:" (pr-str sexpr))
  {})

(defmethod analyze-mm :symbol.local/lookup [env sym]
  (or (grp.art/symbol-detail env sym) {}))

(defmethod analyze-mm :function.external/call [env [f & args]]
  (let [fd       (grp.art/external-function-detail env f)
        argtypes (mapv (partial -analyze! env) args)]
    {::grp.art/samples (grp.sampler/sample! env fd argtypes)}))

(defmethod analyze-mm :function/call [env [function & arguments]]
  (let [current-ns (some-> env ::grp.art/checking-sym namespace)
        fqsym      (if (simple-symbol? function) (symbol current-ns (name function)) function)]
    (log/spy :info [function fqsym])
    (grp.fnt/calculate-function-type env fqsym
      (mapv (partial -analyze! env) arguments))))

(defmethod analyze-mm :function.expression/call [env [fn-expr & args]]
  (let [function (-analyze! env fn-expr)
        argtypes (mapv (partial -analyze! env) args)]
    {::grp.art/samples (grp.sampler/sample! env function argtypes)}))
