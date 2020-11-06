(ns com.fulcrologic.guardrails-pro.checkers.browser
  (:require
    [com.fulcrologic.guardrails-pro.ui.reporter :as reporter]))

(defn start! [opts]
  (reporter/start! opts))

(defn reload! []
  (reporter/hot-reload!))
