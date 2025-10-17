(ns com.fulcrologic.guardrails-analyzer.analysis.analyze-test-utils
  (:require
    [com.fulcrologic.guardrails-analyzer.analysis.analyzer :as cp.ana]
    [com.fulcrologic.guardrails-analyzer.analysis.fdefs.clojure-core]
    [com.fulcrologic.guardrails-analyzer.analysis.fdefs.clojure-spec-alpha]
    [com.fulcrologic.guardrails-analyzer.analysis.fdefs.clojure-string]
    [com.fulcrologicpro.clojure.tools.reader :as reader]))

(defn analyze-string! [env s]
  (cp.ana/analyze! env
    (reader/read-string s)))
