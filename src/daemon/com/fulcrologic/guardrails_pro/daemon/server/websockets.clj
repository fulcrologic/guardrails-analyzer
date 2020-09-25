(ns com.fulcrologic.guardrails-pro.daemon.server.websockets
  (:require
    [mount.core :refer [defstate]]
    [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
    [com.fulcrologic.guardrails-pro.daemon.server.pathom :refer [parser]]
    [com.fulcrologic.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [com.fulcrologic.guardrails-pro.daemon.server.connection-management :refer [listener]]))

(defstate websockets
  :start
  (let [ws (fws/start! (fws/make-websockets parser
                         {:http-server-adapter (get-sch-adapter)
                          :parser-accepts-env? true
                          :sente-options       {:csrf-token-fn nil}}))]
    (wsp/add-listener ws listener)
    ws)
  :stop (fws/stop! websockets))
