(ns com.fulcrologic.guardrails-pro.daemon.middleware
  (:require
    [mount.core :refer [defstate]]
    [com.fulcrologic.fulcro.server.api-middleware :as f.api]
    [ring.middleware.defaults :refer [wrap-defaults]]))

(def ^:private not-found-handler
  (fn [_req]
    {:status  404
     :headers {"Content-Type" "text/plain"}
     :body    "NOPE"}))

;(defn wrap-api [handler uri]
;  (fn [request]
;    (if (= uri (:uri request))
;      (f.api/handle-api-request
;        (:transit-params request)
;        (fn [tx] (parser {:ring/request request} tx)))
;      (handler request))))

(defstate middleware
  :start
  (let [defaults-config {:static {:resources "public"}}]
    (-> not-found-handler
      ;(wrap-api "/api")
      ;f.api/wrap-transit-params
      ;f.api/wrap-transit-response
      (wrap-defaults defaults-config))))
