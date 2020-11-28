(ns com.fulcrologic.copilot.daemon.ui.main-ui
  (:require
    [com.fulcrologic.copilot.ui.viewer :as viewer]))

(defn ^:export init []
  (viewer/start! {}))

(defn ^:export refresh []
  (viewer/hot-reload!))
