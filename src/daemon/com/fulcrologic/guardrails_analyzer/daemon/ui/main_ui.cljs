(ns com.fulcrologic.guardrails-analyzer.daemon.ui.main-ui
  (:require
    [com.fulcrologic.guardrails-analyzer.ui.viewer :as viewer]))

(defn ^:export init []
  (viewer/start! {}))

(defn ^:export refresh []
  (viewer/hot-reload!))
