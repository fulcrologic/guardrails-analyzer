(ns com.fulcrologic.copilot.daemon.server.websockets
  (:require
    [com.fulcrologicpro.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologicpro.fulcro.networking.websockets-client :as fws]
    [com.fulcrologic.copilot.daemon.server.connection-management :as cp.conn]
    [com.fulcrologic.copilot.daemon.server.pathom :refer [parser]]
    [mount.core :refer [defstate]]
    [com.fulcrologicpro.taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]))

(defstate websockets :start
  (let [ws (fws/start! (fws/make-websockets parser
                         {:http-server-adapter (get-sch-adapter)
                          :parser-accepts-env? true
                          :sente-options       {:csrf-token-fn nil}}))]
    (wsp/add-listener ws cp.conn/ws-listener)
    ws)
  :stop (fws/stop! websockets))
