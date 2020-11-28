(ns com.fulcrologic.copilot.checkers.browser
  (:require
    [com.fulcrologic.copilot.ui.checker :as checker]))

(defn start! [opts]
  (checker/start! opts))

(defn reload! []
  (checker/hot-reload!))
