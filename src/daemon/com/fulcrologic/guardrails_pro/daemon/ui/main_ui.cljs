(ns com.fulcrologic.guardrails-pro.daemon.ui.main-ui
  (:require
    [com.fulcrologic.guardrails-pro.ui.root :as root]
    [com.fulcrologic.fulcro.application :as app]))

(defonce app (app/fulcro-app))

(defn ^:export refresh []
  (app/mount! app root/Root "checker"))

(defn ^:export init []
  (app/mount! app root/Root "checker"))
