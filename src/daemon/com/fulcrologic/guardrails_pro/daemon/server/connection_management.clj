(ns com.fulcrologic.guardrails-pro.daemon.server.connection-management
  (:require
    com.wsscode.pathom.connect
    com.wsscode.pathom.core
    [com.fulcrologic.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologic.guardrails-pro.daemon.server.bindings :as bindings]
    [com.fulcrologic.guardrails-pro.daemon.server.problems :as problems]
    [mount.core :refer [defstate]]
    [taoensso.timbre :as log]))

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

(defn update-problems!
  "Send the updated problem list to subscribed websocket viewers."
  [websockets [cid viewer-info]]
  (wsp/push websockets cid :new-problems
    (problems/encode-for viewer-info (problems/get!))))

(defn update-visible-bindings!
  "Sends updated bindings to all viewers"
  [websockets [cid viewer-info]]
  (wsp/push websockets cid :new-bindings
    (bindings/encode-for viewer-info (bindings/get!))))

(defn update-viewers!
  "Update subscribed websocket viewers wrt current problems & bindings."
  ([websockets]
   (let [viewers @subscribed-viewers]
     (doseq [v viewers]
       (update-viewers! websockets v))))
  ([websockets [cid _viewer-info :as viewer]]
   (log/info "Updating viewer:" viewer)
   (wsp/push websockets cid :clear! {})
   (update-problems! websockets viewer)
   (update-visible-bindings! websockets viewer)
   (wsp/push websockets cid :up-to-date {})))
