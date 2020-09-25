(ns com.fulcrologic.guardrails-pro.daemon.server.middleware
  (:require
    [com.fulcrologic.fulcro.server.api-middleware :as f.api]
    [com.fulcrologic.guardrails-pro.daemon.server.config :refer [config]]
    [com.fulcrologic.guardrails-pro.daemon.server.pathom :refer [parser]]
    [com.fulcrologic.guardrails-pro.daemon.server.websockets :refer [websockets]]
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [mount.core :refer [defstate]]
    [ring.middleware.defaults :refer [wrap-defaults]]
    [taoensso.timbre :as log]))

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
  (let [defaults-config (:ring.middleware/defaults-config config)]
    (log/info "Starting with ring defaults config" defaults-config)
    (-> not-found-handler
      (wrap-api "/api")
      (fws/wrap-api websockets)
      f.api/wrap-transit-params
      f.api/wrap-transit-response
      (wrap-defaults defaults-config))))
