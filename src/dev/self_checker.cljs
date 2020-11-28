(ns self-checker
  (:require
    [com.fulcrologic.copilot.checkers.browser :as checker]))

(defn init []
  (checker/start! {}))

(defn reload []
  (checker/reload!))
