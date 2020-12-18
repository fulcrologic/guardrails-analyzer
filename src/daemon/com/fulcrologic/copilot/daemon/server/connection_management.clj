(ns com.fulcrologic.copilot.daemon.server.connection-management
  (:require
    com.wsscode.pathom.connect
    com.wsscode.pathom.core
    [com.fulcrologicpro.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologic.copilot.daemon.server.bindings :as bindings]
    [com.fulcrologic.copilot.daemon.server.problems :as problems]
    [com.fulcrologicpro.taoensso.timbre :as log]
    [mount.core :refer [defstate]]))

(defstate connected-clients :start (atom #{}))
(defstate registered-checkers :start (atom {}))
(defstate subscribed-viewers :start (atom {}))

(def ws-listener
  (reify wsp/WSListener
    (client-added [_ _ cid]
      (swap! connected-clients conj cid))
    (client-dropped [_ _ cid]
      (log/debug "Disconnected ws client:" cid
        (or (get @registered-checkers cid)
          (get @subscribed-viewers cid)))
      (swap! registered-checkers dissoc cid)
      (swap! subscribed-viewers dissoc cid)
      (swap! connected-clients disj cid))))

(defn same-project? [& args]
  (apply = (map :project-dir args)))

(defn viewer->checker [viewer-cid]
  (let [viewer-info (get @subscribed-viewers viewer-cid)]
    (ffirst (filter (fn [[_ checker-info]]
                      (same-project? viewer-info checker-info))
              @registered-checkers))))

(defn get-checker-for [path]
  (->> @registered-checkers
    (filter (fn [[_ checker-info]]
              (.startsWith path (:project-dir checker-info))))
    (ffirst)))

(defn update-problems!
  "Send the updated problem list to subscribed websocket viewers."
  [websockets viewer-cid viewer-info]
  (wsp/push websockets viewer-cid :new-problems
    (problems/encode-for viewer-info
      (problems/get! (viewer->checker viewer-cid)))))

(defn update-visible-bindings!
  "Sends updated bindings to all viewers"
  [websockets viewer-cid viewer-info]
  (wsp/push websockets viewer-cid :new-bindings
    (bindings/encode-for viewer-info
      (bindings/get! (viewer->checker viewer-cid)))))

;; CONTEXT: update viewer's problems & bindings
(defn update-viewer! [websockets viewer-cid viewer-info]
  (log/debug "Updating viewer:" viewer-cid)
  (wsp/push websockets viewer-cid :clear! {})
  (update-problems! websockets viewer-cid viewer-info)
  (update-visible-bindings! websockets viewer-cid viewer-info)
  (wsp/push websockets viewer-cid :up-to-date {}))

;; CONTEXT: updates viewers for checker-cid's project
(defn update-viewers-for! [websockets checker-cid]
  (let [viewers @subscribed-viewers
        checker-info (get @registered-checkers checker-cid)]
    (doseq [[viewer-cid viewer-info] viewers]
      (when (same-project? viewer-info checker-info)
        (update-viewer! websockets viewer-cid viewer-info)))))

;; CONTEXT: reports this error to appropriate viewers
(defn report-error!
  [websockets checker-cid error]
  (let [checker-info (get @registered-checkers checker-cid)]
    (doseq [[viewer-cid viewer-info] @subscribed-viewers]
      (when (same-project? viewer-info checker-info)
        (wsp/push websockets viewer-cid :error error)))))
