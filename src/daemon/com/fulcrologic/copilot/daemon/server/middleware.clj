(ns com.fulcrologic.copilot.daemon.server.middleware
  (:require
    [com.fulcrologicpro.fulcro.networking.websockets :as f.ws]
    [com.fulcrologicpro.fulcro.server.api-middleware :as f.api]
    [com.fulcrologic.copilot.daemon.server.config :refer [config]]
    [com.fulcrologic.copilot.daemon.server.pathom :refer [parser]]
    [com.fulcrologic.copilot.daemon.server.websockets :refer [websockets]]
    [com.fulcrologic.copilot.transit-handlers :as cp.transit]
    [mount.core :refer [defstate]]
    [ring.middleware.defaults :refer [wrap-defaults]]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(def ^:private not-found-handler
  (fn [_req]
    {:status  404
     :headers {"Content-Type" "text/plain"}
     :body    "NOPE"}))

(defn wrap-api [handler uri]
  (fn [request]
    (if (= uri (:uri request))
      (f.api/handle-api-request
        (:transit-params request)
        (fn [tx] (parser {:ring/request request} tx)))
      (handler request))))

(defstate middleware
  :start
  (let [defaults-config     (:ring.middleware/defaults-config config)
        transit-writer-opts {:opts {:default-handler cp.transit/default-write-handler}}]
    (log/info "Starting with ring defaults config" defaults-config)
    (-> not-found-handler
      (wrap-api "/api")
      (f.ws/wrap-api websockets)
      (f.api/wrap-transit-params)
      (f.api/wrap-transit-response transit-writer-opts)
      (wrap-defaults defaults-config))))
