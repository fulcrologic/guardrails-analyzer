(ns com.fulcrologic.guardrails-pro.checkers.sente-client
  (:require
    [com.fulcrologic.guardrails-pro.transit-handlers :as grp.transit]
    [com.fulcrologic.guardrails-pro.logging :as log])
  (:import
    (java.net URI)
    (java.util UUID)
    (com.fulcrologic_pro.org.java_websocket.client WebSocketClient)
    (com.fulcrologic_pro.org.java_websocket.enums ReadyState)
    (com.fulcrologic_pro.org.java_websocket.exceptions WebsocketNotConnectedException)))

(defn- send-edn! [^WebSocketClient client edn]
  (.send client (str "+" (grp.transit/write-edn edn))))

(defonce msg-cbs (atom {}))

(defn send! [{:as env ::keys [^WebSocketClient client]} edn & [cb]]
  (let [ready-state (.getReadyState client)]
    (log/debug "ws.readyState:" ready-state)
    (if (= ready-state ReadyState/OPEN)
      (try
        (let [msg-id (subs (str (UUID/randomUUID)) 0 6)]
          (when cb (swap! msg-cbs assoc msg-id cb))
          (send-edn! client [edn msg-id]))
        (catch WebsocketNotConnectedException e
          (log/error e)))
      (log/warn "WIP: DEV: websocket was not open!"))))

(defmulti ^:private on-ws-msg!
  (fn [env edn]
    (cond
      (keyword? edn) edn
      (vector? edn)
      (cond
        (keyword? (first edn)) (first edn)
        (map? (first edn)) ::response
        (vector? (first edn)) ::messages
        :else :default))))

(defmethod on-ws-msg! :chsk/ws-ping [_ _] nil)
(defmethod on-ws-msg! :chsk/handshake
  [{:as env :keys [on-connect]} _]
  (on-connect env))

(defmethod on-ws-msg! ::response [_ [response msg-id]]
  (when-let [cb (get msg-cbs msg-id)]
    (cb response)))

(defmethod on-ws-msg! ::messages [{:as env :keys [on-message]} messages]
  (doseq [msg messages]
    (on-message env msg)))

(defmethod on-ws-msg! :default [_ edn]
  (log/warn :on-ws-msg/default edn))

(declare reconnect!)

(defn with-client [env client]
  (assoc env ::client client))

(defn make-ws-client
  [uri {:as env :keys [on-connect on-disconnect on-error]}]
  (doto
    (proxy [WebSocketClient] [uri]
      (onOpen [_hs]
        (log/debug "connected:" uri))
      (onError [e]
        (log/error e "error:")
        (on-error (with-client env this) e))
      (onClose [code reason _]
        (log/info "disconnected:" {:code code :reason reason})
        (let [env (with-client env this)]
          (on-disconnect env code reason)
          (future (reconnect! env))))
      (onMessage [m]
        (let [message (subs m 1)
              env (with-client env this)]
          (try
            (on-ws-msg! env
              (grp.transit/read-edn message))
            (catch Throwable e
              (log/error e "Failed to process message because:")
              (on-error env e))))))
    (.connectBlocking)))

(defn connect-impl! [{:as env ::keys [host] :keys [?port-fn]}]
  (loop []
    (if-let [port (?port-fn)]
      (let [uri (new URI (str "ws://" host ":" port
                           "/chsk?client-id=" (UUID/randomUUID)))]
        (make-ws-client uri env)
        nil)
      (do
        (Thread/sleep 3000)
        (recur)))))

(defn connect! [host {:as opts :keys [?port-fn]}]
  {:pre [(fn? ?port-fn)
         (fn? (:on-message opts))]}
  (let [env (merge
              {:on-error (constantly nil)
               :on-connect (constantly nil)
               :on-disconnect (constantly nil)}
              opts
              {::host host})]
    (try (connect-impl! env)
      (catch Throwable t
        (log/error "error connecting:" t)
        (throw t)))))

(defn reconnect!
  [{:as env ::keys [^WebSocketClient client]}]
  (try
    (log/info "trying to reconnect!")
    (connect-impl! env)
    (catch Throwable t
      (log/error "error reconnecting:" t)
      (throw t))))
