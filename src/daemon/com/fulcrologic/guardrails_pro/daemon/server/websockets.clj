(ns com.fulcrologic.guardrails-pro.daemon.server.websockets
  (:require
    [cognitect.transit :as transit]
    [mount.core :refer [defstate]]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.algorithms.transit :as ot]
    [com.fulcrologic.fulcro.networking.transit-packer :as tp]
    [com.fulcrologic.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [com.fulcrologic.guardrails-pro.daemon.server.connection-management :refer [listener]]
    [com.fulcrologic.guardrails-pro.daemon.server.pathom :refer [parser]]
    [taoensso.sente.packers.transit :as st]
    [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]])
  (:import
    (com.cognitect.transit ReadHandler)
    (com.fulcrologic.fulcro.algorithms.tempid TempId)))

(defn make-packer [{:keys [read write]}]
  (st/->TransitPacker :json
    {:handlers (cond-> {TempId (ot/->TempIdHandler)}
                 write (merge write))
     :transform transit/write-meta}
    {:handlers (cond-> {tempid/tag (reify ReadHandler
                                     (fromRep [_ id] (TempId. id)))}
                 read (merge read))}))

(defstate websockets :start
  ;; NOTE: hack until :transform opt is supported in fws
  (let [ws (with-redefs [tp/make-packer make-packer]
             (fws/start! (fws/make-websockets parser
                           {:http-server-adapter (get-sch-adapter)
                            :parser-accepts-env? true
                            :sente-options       {:csrf-token-fn nil}})))]
    (wsp/add-listener ws listener)
    ws)
  :stop (fws/stop! websockets))
