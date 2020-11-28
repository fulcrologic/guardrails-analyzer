(ns com.fulcrologic.copilot.daemon.server.config
  (:require
    [mount.core :refer [defstate args]]
    [com.fulcrologicpro.fulcro.server.config :refer [load-config!]]
    [com.fulcrologic.copilot.logging :as log]))

(defstate config
  :start (let [{:keys [config] :or {config "config/dev.edn"}} (args)
               configuration (load-config! {:config-path config})]
           (log/info "Loaded config" config)
           configuration))
