(ns com.fulcrologic.copilot.analysis.analyze-test-utils
  (:require
    [com.fulcrologic.copilot.analysis.analyzer :as cp.ana]
    [com.fulcrologicpro.clojure.tools.reader :as reader]))

(defn analyze-string! [env s]
  (cp.ana/analyze! env
    (reader/read-string s)))
