(ns com.fulcrologic.copilot.analysis.analyze-test-utils
  (:require
    [com.fulcrologic.copilot.analysis.analyzer :as cp.ana]
    [com.fulcrologic.copilot.analysis.fdefs.clojure-core]
    [com.fulcrologic.copilot.analysis.fdefs.clojure-spec-alpha]
    [com.fulcrologic.copilot.analysis.fdefs.clojure-string]
    [com.fulcrologicpro.clojure.tools.reader :as reader]))

(defn analyze-string! [env s]
  (cp.ana/analyze! env
    (reader/read-string s)))
