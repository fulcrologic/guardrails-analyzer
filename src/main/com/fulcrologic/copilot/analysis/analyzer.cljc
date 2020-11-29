(ns com.fulcrologic.copilot.analysis.analyzer
  (:require
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as grp.disp]
    [com.fulcrologic.copilot.analysis.analyzer.literals]
    [com.fulcrologic.copilot.analysis.analyzer.functions]
    [com.fulcrologic.copilot.analysis.analyzer.macros]
    [com.fulcrologic.copilot.analysis.analyzer.hofs]
    [com.fulcrologic.copilot.analysis.function-type :as grp.fnt]
    [com.fulcrologic.copilot.analytics :as grp.analytics]
    [com.fulcrologic.copilot.artifacts :as grp.art]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn =>]]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(>defn analyze!
  [env sexpr]
  [::grp.art/env any? => ::grp.art/type-description]
  (grp.disp/-analyze! env sexpr))

(defmethod grp.disp/analyze-mm :unknown [env sexpr]
  (log/error "Unknown expression:" (pr-str sexpr))
  (grp.disp/unknown-expr env sexpr))

(defmethod grp.disp/analyze-mm :symbol/lookup [env sym]
  (or
    (grp.art/symbol-detail env sym)
    (grp.art/function-detail env sym)
    (grp.art/external-function-detail env sym)
    (grp.disp/unknown-expr env sym)))

(defmethod grp.disp/analyze-mm :function.external/call [env [f & args :as sexpr]]
  (grp.analytics/with-analytics env sexpr true
    (fn [env]
      (let [function (grp.art/external-function-detail env f)
            argtypes (mapv (partial grp.disp/-analyze! env) args)]
        (grp.fnt/analyze-function-call! env function argtypes)))))

(defmethod grp.disp/analyze-mm :function/call [env [f & arguments]]
  (let [function (grp.art/function-detail env f)
        argtypes (mapv (partial grp.disp/-analyze! env) arguments)]
    (grp.fnt/analyze-function-call! env function argtypes)))

(defmethod grp.disp/analyze-mm :function.expression/call [env [fn-expr & args]]
  (let [function (grp.disp/-analyze! env fn-expr)
        argtypes (mapv (partial grp.disp/-analyze! env) args)]
    (grp.fnt/analyze-function-call! env function argtypes)))
