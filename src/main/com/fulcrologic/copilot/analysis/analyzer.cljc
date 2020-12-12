(ns com.fulcrologic.copilot.analysis.analyzer
  (:require
    com.fulcrologic.copilot.analysis.analyzer.hofs
    com.fulcrologic.copilot.analysis.analyzer.ifn
    com.fulcrologic.copilot.analysis.analyzer.literals
    com.fulcrologic.copilot.analysis.analyzer.macros
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as cp.ana.disp]
    [com.fulcrologic.copilot.analysis.function-type :as cp.fnt]
    [com.fulcrologic.copilot.analytics :as cp.analytics]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn =>]]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(>defn analyze!
  [env sexpr]
  [::cp.art/env any? => ::cp.art/type-description]
  (cp.ana.disp/-analyze! env sexpr))

(defmacro defanalyzer [disp arglist & body]
  `(defmethod cp.ana.disp/analyze-mm '~disp ~arglist ~@body))

(defmethod cp.ana.disp/analyze-mm :unknown [env sexpr]
  (log/error "Unknown expression:" (pr-str sexpr))
  (cp.ana.disp/unknown-expr env sexpr))

(defmethod cp.ana.disp/analyze-mm :symbol.local/lookup [env sym]
  (or
    (cp.art/symbol-detail env sym)
    (cp.art/function-detail env sym)
    (cp.art/external-function-detail env sym)
    (cp.ana.disp/unknown-expr env sym)))

(defmethod cp.ana.disp/analyze-mm :symbol.local/call [env [sym & args]]
  (let [function (cp.art/symbol-detail env sym)
        argtypes (mapv (partial cp.ana.disp/-analyze! env) args)]
    (cp.fnt/analyze-function-call! env function argtypes)))

(defmethod cp.ana.disp/analyze-mm :function.external/call [env [f & args :as sexpr]]
  (cp.analytics/with-analytics env sexpr true
    (fn [env]
      (let [function (cp.art/external-function-detail env f)
            argtypes (mapv (partial cp.ana.disp/-analyze! env) args)]
        (cp.fnt/analyze-function-call! env function argtypes)))))

(defmethod cp.ana.disp/analyze-mm :function/call [env [f & arguments]]
  (let [function (cp.art/function-detail env f)
        argtypes (mapv (partial cp.ana.disp/-analyze! env) arguments)]
    (cp.fnt/analyze-function-call! env function argtypes)))

(defmethod cp.ana.disp/analyze-mm :function.expression/call [env [fn-expr & args]]
  (let [function (cp.ana.disp/-analyze! env fn-expr)
        argtypes (mapv (partial cp.ana.disp/-analyze! env) args)]
    (cp.fnt/analyze-function-call! env function argtypes)))
