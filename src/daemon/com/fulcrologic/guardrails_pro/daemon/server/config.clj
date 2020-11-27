(ns com.fulcrologic.guardrails-pro.daemon.server.config
  (:require
    [mount.core :refer [defstate args]]
    [com.fulcrologicpro.fulcro.server.config :refer [load-config!]]
    [com.fulcrologic.guardrails-pro.logging :as log]))

(defstate config
  :start (let [{:keys [config] :or {config "config/dev.edn"}} (args)
               configuration (load-config! {:config-path config})]
           (log/info "Loaded config" config)
           configuration))
