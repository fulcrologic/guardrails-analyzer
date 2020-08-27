(ns com.fulcrologic.guardrails-pro.daemon.http-server
  (:require
    [com.fulcrologic.guardrails-pro.daemon.middleware :refer [middleware]]
    [mount.core :refer [defstate]]
    [org.httpkit.server :as http-kit]
    [taoensso.timbre :as log]))

(defstate http-server
  :start
  (let [cfg {:port 3000}]
    (log/info "Starting HTTP Server with config " (pr-str cfg))
    (http-kit/run-server middleware cfg))
  :stop (http-server))
