(ns com.fulcrologic.copilot.daemon.server.websockets
  (:require
    [com.fulcrologic.copilot.daemon.server.connection-management :as cp.conn]
    [com.fulcrologic.copilot.daemon.server.pathom :refer [parser]]
    [com.fulcrologicpro.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologicpro.fulcro.networking.websockets :as fws]
    [com.fulcrologicpro.taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
    [mount.core :refer [defstate]]))

(defstate websockets :start
  (let [ws (fws/start! (fws/make-websockets parser
                         {:http-server-adapter (get-sch-adapter)
                          :parser-accepts-env? true
                          :sente-options       {:csrf-token-fn nil}}))]
    (wsp/add-listener ws cp.conn/ws-listener)
    ws)
  :stop (fws/stop! websockets))
