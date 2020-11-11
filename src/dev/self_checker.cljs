(ns self-checker
  (:require
    [com.fulcrologic.guardrails-pro.checkers.browser :as checker]))

(defn init []
  (checker/start! {}))

(defn reload []
  (checker/reload!))
