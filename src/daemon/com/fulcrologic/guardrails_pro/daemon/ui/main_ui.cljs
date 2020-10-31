(ns com.fulcrologic.guardrails-pro.daemon.ui.main-ui
  (:require
    [com.fulcrologic.fulcro.application :as f.app]
    [com.fulcrologic.fulcro.data-fetch :as f.df]
    [com.fulcrologic.guardrails-pro.ui.reporter :as reporter]))

(defn ^:export refresh [] (reporter/hot-reload!))

(defn ^:export init [] (reporter/start! {:checker? false}))
