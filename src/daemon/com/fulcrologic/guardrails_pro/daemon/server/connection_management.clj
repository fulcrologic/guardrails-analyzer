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
(defstate registered-checkers :start (atom #{}))
(defstate subscribed-viewers :start (atom #{}))

(def listener (reify wsp/WSListener
                (client-added [_ _ cid]
                  (swap! connected-clients conj cid))
                (client-dropped [_ _ cid]
                  (swap! registered-checkers disj cid)
                  (swap! subscribed-viewers disj cid)
                  (swap! connected-clients disj cid))))

(defn clear-viewer-data!
  "Send the updated problem list to subscribed websocket viewers."
  [websockets only-cid]
  (wsp/push websockets only-cid :clear! {}))

(defn update-problems!
  "Send the updated problem list to subscribed websocket viewers."
  [websockets only-cid]
  (wsp/push websockets only-cid :new-problems (problems/get!)))

(defn update-visible-bindings!
  "Sends updated bindings to all viewers"
  [websockets only-cid]
  (wsp/push websockets only-cid :new-bindings (bindings/get!)))

(defn update-viewers!
  "Send the updated problem list to subscribed websocket viewers."
  ([websockets]
   (let [ui-cids @subscribed-viewers]
     (log/info ui-cids)
     (doseq [cid ui-cids]
       (log/info "Notifying viewer of new problems " cid)
       (update-viewers! websockets cid))))
  ([websockets only-cid]
   (clear-viewer-data! websockets only-cid)
   (update-problems! websockets only-cid)
   (update-visible-bindings! websockets only-cid)))
