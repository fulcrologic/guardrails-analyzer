(ns com.fulcrologic.guardrails-pro.daemon.server.websockets
  (:require
    [mount.core :refer [defstate]]
    [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
    [com.fulcrologic.guardrails-pro.daemon.server.pathom :refer [parser]]
    [com.fulcrologic.fulcro.networking.websockets :as fws]))

(defstate websockets
  :start
  (fws/start! (fws/make-websockets
                parser
                {:http-server-adapter (get-sch-adapter)
                 :parser-accepts-env? true
                 :sente-options       {:csrf-token-fn nil}}))
  :stop
  (fws/stop! websockets))
