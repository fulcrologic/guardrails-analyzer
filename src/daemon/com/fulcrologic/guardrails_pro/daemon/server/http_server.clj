(ns com.fulcrologic.guardrails-pro.daemon.server.http-server
  (:require
    [com.fulcrologic.guardrails-pro.daemon.server.config :refer [config]]
    [com.fulcrologic.guardrails-pro.daemon.server.middleware :refer [middleware]]
    [mount.core :refer [defstate]]
    [org.httpkit.server :as http-kit]
    [taoensso.timbre :as log]))

(defstate http-server
  :start
  (let [cfg (::http-kit/config config)]
    (log/info "Starting HTTP Server with config: " (pr-str cfg))
    (http-kit/run-server middleware cfg))
  :stop (http-server))
