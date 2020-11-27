(ns com.fulcrologic.guardrails-pro.daemon.server.websockets
  (:require
    [com.fulcrologicpro.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologicpro.fulcro.networking.websockets :as fws]
    [com.fulcrologic.guardrails-pro.daemon.server.connection-management :refer [listener]]
    [com.fulcrologic.guardrails-pro.daemon.server.pathom :refer [parser]]
    [mount.core :refer [defstate]]
    [com.fulcrologicpro.taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]))

(defstate websockets :start
  (let [ws (fws/start! (fws/make-websockets parser
                         {:http-server-adapter (get-sch-adapter)
                          :parser-accepts-env? true
                          :sente-options       {:csrf-token-fn nil}}))]
    (wsp/add-listener ws listener)
    ws)
  :stop (fws/stop! websockets))
