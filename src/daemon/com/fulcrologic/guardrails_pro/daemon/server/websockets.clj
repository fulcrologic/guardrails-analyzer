(ns com.fulcrologic.guardrails-pro.daemon.server.websockets
  (:require
    [mount.core :refer [defstate]]
    [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
    [com.fulcrologic.guardrails-pro.daemon.server.pathom :refer [parser]]
    [com.fulcrologic.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [clojure.set :as set]
    [taoensso.timbre :as log]
    [com.fulcrologic.guardrails-pro.daemon.server.connection-management :as cmgmt]
    [com.fulcrologic.guardrails-pro.daemon.server.problems :as problems]))

(defstate websockets
  :start
  (let [ws (fws/start! (fws/make-websockets
                         parser
                         {:http-server-adapter (get-sch-adapter)
                          :parser-accepts-env? true
                          :sente-options       {:csrf-token-fn nil}}))]
    (wsp/add-listener ws cmgmt/listener)
    ws)
  :stop
  (fws/stop! websockets))

