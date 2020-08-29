(ns com.fulcrologic.guardrails-pro.daemon.server.middleware
  (:require
    [clojure.data.json :as json]
    [com.fulcrologic.fulcro.server.api-middleware :as f.api]
    [com.fulcrologic.guardrails-pro.daemon.server.config :refer [config]]
    [com.fulcrologic.guardrails-pro.daemon.server.pathom :refer [parser]]
    [com.fulcrologic.guardrails-pro.daemon.server.problems :as problems]
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

(defn wrap-problems [handler]
  (fn [request]
    (if (= "/problems" (:uri request))
      (let [{:strs [editor file]} (:query-params request)]
        (log/debug "getting problems for file:" file "and editor:" editor)
        (case (:request-method request)
          :get {:body (json/write-str
                        (problems/format-for editor
                          (problems/get! file)))}
          :post (do (problems/set! {}) {})
          :delete (do (problems/clear!) {})
          (handler request)))
      (handler request))))

(defstate middleware
  :start
  (let [defaults-config (:ring.middleware/defaults-config config)]
    (log/info "Starting with ring defaults config" defaults-config)
    (-> not-found-handler
      (wrap-api "/api")
      f.api/wrap-transit-params
      f.api/wrap-transit-response
      wrap-problems
      (wrap-defaults defaults-config))))
