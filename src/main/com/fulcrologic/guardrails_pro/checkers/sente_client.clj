(ns com.fulcrologic.guardrails-pro.checkers.sente-client
  (:require
    [com.fulcrologic.guardrails-pro.transit-handlers :as grp.transit])
  (:import
    java.net.URI
    org.eclipse.jetty.util.ssl.SslContextFactory
    (org.eclipse.jetty.websocket.api
      WebSocketListener RemoteEndpoint Session)
    (org.eclipse.jetty.websocket.client
      ClientUpgradeRequest WebSocketClient)))

(set! *warn-on-reflection* true)

;; ## Messages

(defn- send-edn! [^RemoteEndpoint remote edn]
  @(.sendStringByFuture remote (str "+" (grp.transit/write-edn edn))))

(defprotocol SenteClient
  (send! [this msg]
    "Sends edn to the given Sente WebSocket using transit.")
  (close [this]
    "Closes the WebSocket."))

(defn- deref-session ^Session [session-promise]
  (let [result @session-promise]
    (if (instance? Throwable result)
      (throw result)
      result)))

(defn- ws-listener
  ^WebSocketListener
  [sente-client session-promise
   {:keys [on-connect on-receive on-error on-close]
    :or {on-connect (constantly nil)
         on-receive (constantly nil)
         on-error   (constantly nil)
         on-close   (constantly nil)}}]
  (reify WebSocketListener
    (onWebSocketText [_ msg]
      (try
        (on-receive sente-client
          (grp.transit/read-edn (subs msg 1)))
        (catch Throwable e
          (prn ::caught e)
          (on-error e))))
    (onWebSocketError [_ throwable]
      (if (realized? session-promise)
        (on-error throwable)
        (deliver session-promise throwable)))
    (onWebSocketConnect [_ session]
      (deliver session-promise session)
      (on-connect sente-client))
    (onWebSocketClose [_ x y]
      (on-close x y))))

(defn- ws-client
  (^WebSocketClient
    [] (new WebSocketClient))
  (^WebSocketClient
    [^URI uri]
    (if (= "wss" (.getScheme uri))
      (new WebSocketClient (new SslContextFactory))
      (new WebSocketClient))))

(defn- connect-ws!
  [^WebSocketClient ws-client ^URI uri {:as opts ::keys [cleanup]}]
  (let [session-promise (promise)
        sente-client (reify SenteClient
                       (send! [_ msg]
                         (-> (deref-session session-promise)
                           (.getRemote)
                           (send-edn! msg)))
                       (close [_]
                         (when cleanup
                           (cleanup))
                         (.close (deref-session session-promise))))
        listener (ws-listener sente-client session-promise opts)]
     (.connect ws-client listener uri
       (new ClientUpgradeRequest))
     sente-client))

;; ## API

(defn connect! [base-uri opts]
  (let [uri (new URI base-uri)
        ws-client (ws-client uri)]
    (try
      (.start ws-client)
      (->> (assoc opts ::cleanup #(.stop ws-client))
        (connect-ws! ws-client uri))
      (catch Throwable ex
        (.stop ws-client)
        (throw ex)))))
