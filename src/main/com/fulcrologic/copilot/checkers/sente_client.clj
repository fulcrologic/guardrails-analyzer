(ns com.fulcrologic.copilot.checkers.sente-client
  {:clojure.tools.namespace.repl/load false}
  (:refer-clojure :exclude [run!])
  (:require
    [com.fulcrologic.copilot.transit-handlers :as cp.transit]
    [com.fulcrologicpro.taoensso.timbre :as log])
  (:import
    (java.net URI)
    (java.util UUID)
    (com.fulcrologicpro.org.java_websocket.client WebSocketClient)
    (com.fulcrologicpro.org.java_websocket.enums ReadyState)
    (com.fulcrologicpro.org.java_websocket.exceptions WebsocketNotConnectedException)))

;; only one active client allowed to be started at a time
(def default-client-options (atom {}))
(def active-client (atom nil))
(def msg-cbs (atom {}))

(defn- send-edn! [^WebSocketClient client edn]
  (.send client (str "+" (cp.transit/write-edn edn))))

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

(defn connect! [^WebSocketClient client] (.connectBlocking client))
(defn reconnect! [^WebSocketClient client] (.reconnectBlocking client))

(defn websocket-client
  "Returns a subclass of WebSocketClient that can call your handlers on given events."
  [{:keys [host on-connect on-disconnect on-error ?port-fn] :as opts}]
  (let [port (?port-fn)
        uri  (new URI (str "ws://" host ":" port "/chsk?client-id=" (UUID/randomUUID)))
        env  (merge
               {:on-error      (constantly nil)
                :on-connect    (constantly nil)
                :on-disconnect (constantly nil)}
               opts
               {::host host
                :uri   uri})]
    (proxy [WebSocketClient] [uri]
      (onOpen [_hs]
        (log/info "Opened connection to " uri))
      (onError [e]
        (when on-error
          (on-error (assoc env ::client this) e)))
      (onClose [code reason remote-caused?]
        (log/info "disconnected:" {:code code :reason reason :remote? remote-caused?})
        (try
          (when on-disconnect
           (on-disconnect (assoc env ::client this) code reason remote-caused?))
          (catch Exception e
            (log/error e "onClose")))
        (when (pos? code)
          (let [client this]
            (future
              (loop []
                (if (= @active-client client)
                  (do
                    (Thread/sleep 2000)
                    (log/info "Attempting to reconnect...")
                    (if (reconnect! client)
                      (log/info "Reconnected.")
                      (recur)))
                  (log/info "Client closed because it was shut down.")))))))
      (onMessage [m]
        (let [message (subs m 1)]
          (try
            (on-ws-msg! (assoc env ::client this) (cp.transit/read-edn message))
            (catch Throwable e
              (log/error e "Failed to process message because:")
              (on-error (assoc env ::client this) e))))))))

(defn shutdown!
  "Close the active client."
  []
  (when-let [c ^WebSocketClient @active-client]
    (log/info "Shutting down prior (running) client.")
    (reset! active-client nil)
    (.close c)))

(defn run!
  "Creates a new active websocket client that will maintain a websocket connection with the given options.
   This function will shut down any prior running websocket.

  Returns true if the client is started, and false if copilotd isn't found."
  [{:keys [host on-connect on-disconnect on-error ?port-fn] :as opts}]
  (shutdown!)
  (if-let [port (?port-fn)]
    (let [client (websocket-client opts)]
      (log/info "Attempting to connect to" port)
      (if (connect! client)
        (do
          (log/info "Connected!")
          (reset! active-client client))
        (log/error "Connection failed. Port was defined, but no copilotd responded.")))
    (log/error "No copilot daemon found. Have you started copilotd? Are you running copilotd in the same directory as the project root?"))
  (boolean @active-client))
