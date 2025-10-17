(ns self-checker
  (:require
    [com.fulcrologic.guardrails-analyzer.checkers.browser :as checker]))

(defn init []
  (checker/start! {}))

(defn reload []
  (checker/reload!))
