(ns com.fulcrologic.guardrails-pro.checkers.browser
  (:require
    [com.fulcrologic.guardrails-pro.ui.checker :as checker]))

(defn start! [opts]
  (checker/start! opts))

(defn reload! []
  (checker/hot-reload!))
