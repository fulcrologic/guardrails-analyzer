(ns com.fulcrologic.guardrails-pro.daemon.ui.main-ui
  (:require
    [com.fulcrologic.guardrails-pro.ui.viewer :as viewer]))

(defn ^:export init []
  (viewer/start! {}))

(defn ^:export refresh []
  (viewer/hot-reload!))
