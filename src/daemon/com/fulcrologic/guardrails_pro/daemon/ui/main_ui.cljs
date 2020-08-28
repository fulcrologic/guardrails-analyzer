(ns com.fulcrologic.guardrails-pro.daemon.ui.main-ui
  (:require
    [com.fulcrologic.fulcro.application :as f.app]
    [com.fulcrologic.fulcro.data-fetch :as f.df]
    [com.fulcrologic.fulcro.networking.http-remote :as f.http]
    [com.fulcrologic.guardrails-pro.daemon.ui.root :as ui]))

(defonce app
  (f.app/fulcro-app
    {:started-callback
     (fn [app] (f.df/load! app :all-problems nil))
     :remotes
     {:remote (f.http/fulcro-http-remote {})}}))

(defn ^:export refresh []
  (f.app/mount! app ui/Root "checker"))

(defn ^:export init []
  (refresh))
