(ns com.fulcrologic.guardrails-pro.daemon.server.connection-management
  (:require
    com.wsscode.pathom.connect
    com.wsscode.pathom.core
    [mount.core :refer [defstate]]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologic.guardrails-pro.daemon.server.problems :as problems]
    [com.fulcrologic.guardrails-pro.daemon.server.bindings :as bindings]))

(defstate connected-clients :start (atom #{}))
(defstate registered-checkers :start (atom {}))
(defstate subscribed-viewers :start (atom {}))

(def listener (reify wsp/WSListener
                (client-added [_ _ cid]
                  (swap! connected-clients conj cid))
                (client-dropped [_ _ cid]
                  (log/debug "Disconnected ws client:" cid)
                  (swap! registered-checkers dissoc cid)
                  (swap! subscribed-viewers dissoc cid)
                  (swap! connected-clients disj cid))))

(defn clear-viewer-data!
  "Send the updated problem list to subscribed websocket viewers."
  [websockets cid]
  (wsp/push websockets cid :clear! {}))

(defn update-problems!
  "Send the updated problem list to subscribed websocket viewers."
  [websockets [cid viewer-info]]
  (wsp/push websockets cid :new-problems
    (problems/encode-for viewer-info (problems/get!))))

(defn update-visible-bindings!
  "Sends updated bindings to all viewers"
  [websockets [cid viewer-info]]
  (wsp/push websockets cid :new-bindings (bindings/get!)))

(defn update-viewers!
  "Send the updated problem list to subscribed websocket viewers."
  ([websockets]
   (let [viewers @subscribed-viewers]
     (doseq [v viewers]
       (update-viewers! websockets v))))
  ([websockets [cid _viewer-info :as viewer]]
   (log/info "Updating viewer:" viewer)
   (clear-viewer-data! websockets cid)
   (update-problems! websockets viewer)
   (update-visible-bindings! websockets viewer)))
