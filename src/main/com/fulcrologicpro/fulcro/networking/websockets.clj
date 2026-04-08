(ns com.fulcrologicpro.fulcro.networking.websockets
  (:require
    [com.fulcrologicpro.fulcro.networking.transit-packer :as tp]
    [com.fulcrologicpro.fulcro.networking.websocket-protocols :refer [WSListener WSNet add-listener remove-listener client-added client-dropped]]
    [com.fulcrologicpro.fulcro.server.api-middleware :as server]
    [com.fulcrologicpro.taoensso.sente :refer [make-channel-socket-server! start-server-chsk-router!]]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defn sente-event-handler
  "A sente event handler that connects the websockets support up to the parser via the
  :fulcro.client/API event, and also handles notifying listeners that clients connected and dropped."
  [{:keys [send-fn listeners parser parser-accepts-env?] :as websockets} event]
  (let [env    (merge {:push          send-fn
                       :websockets    websockets
                       :cid           (:client-id event)    ; legacy. might be removed
                       :user-id       (:uid event)
                       :request       (:ring-req event)     ; legacy. might be removed
                       :sente-message event}
                 (dissoc websockets :server-options :ring-ajax-get-or-ws-handshake :ring-ajax-post
                   :ch-recv :send-fn :stop-fn :listeners))
        parser (if parser-accepts-env? (partial parser env) parser)
        {:keys [?reply-fn id uid ?data]} event]
    (case id
      :chsk/uidport-open (doseq [^WSListener l @listeners]
                           (log/debug (str "Notifying listener that client " uid " connected"))
                           (client-added l websockets uid))
      :chsk/uidport-close (doseq [^WSListener l @listeners]
                            (log/debug (str "Notifying listener that client " uid " disconnected"))
                            (client-dropped l websockets uid))
      :fulcro.client/API (let [result (server/handle-api-request ?data parser)]
                           (if ?reply-fn
                             (try
                               (?reply-fn result)
                               (catch Exception e
                                 (log/error "Failed to encode result onto websocket. Make sure your query or mutation returned a properly serializable value.  The errant value was: " result)))
                             (log/error "Reply function missing on API call!")))
      :chsk/bad-event (log/error "Corrupt message. Websocket client sent a corrupt message." event)
      nil (log/error "Sente event handler received a nil event ID in event" event ". This indicates a corrupt websocket message from the client, or a failure in transit encode/decode.")
      (do :nothing-by-default))))

(defn- is-wsrequest? [{:keys [websockets-uri]} {:keys [uri]}]
  (= websockets-uri uri))

(defrecord Websockets [parser server-adapter server-options transit-handlers
                       ring-ajax-post ring-ajax-get-or-ws-handshake websockets-uri
                       ch-recv send-fn connected-uids stop-fn listeners
                       parser-accepts-env?]
  WSNet
  (add-listener [this listener]
    (log/info "Adding channel listener to websockets")
    (swap! listeners conj listener))
  (remove-listener [this listener]
    (log/info "Removing channel listener from websockets")
    (swap! listeners disj listener))
  (push [this cid verb edn]
    (send-fn cid [:api/server-push {:topic verb :msg edn}])))

(defn start!
  "Start sente websockets. Returns the updated version of the websockets, which should be saved for calling `stop`."
  [{:keys [transit-handlers server-adapter server-options] :as this}]
  (log/info "Starting Sente websockets support")
  (let [transit-handlers (or transit-handlers {})
        chsk-server      (make-channel-socket-server!
                           server-adapter (merge {:packer (tp/make-packer transit-handlers)}
                                            server-options))
        {:keys [ch-recv send-fn connected-uids
                ajax-post-fn ajax-get-or-ws-handshake-fn]} chsk-server
        result           (assoc this
                           :ring-ajax-post ajax-post-fn
                           :ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn
                           :ch-rech ch-recv
                           :send-fn send-fn
                           :listeners (atom #{})
                           :connected-uids connected-uids)
        stop             (start-server-chsk-router! ch-recv (partial sente-event-handler result))]
    (log/info "Started Sente websockets event loop.")
    (assoc result :stop-fn stop)))

(defn stop!
  "Stop websockets service.  Returns an updated version of the websockets."
  [{:keys [stop-fn] :as this}]
  (when stop-fn
    (log/info "Stopping websockets.")
    (stop-fn))
  (log/info "Stopped websockets.")
  (assoc this :stop-fn nil :ch-recv nil :send-fn nil))

(defn make-websockets
  "Build a web sockets component with the given API parser and sente socket server options (see sente docs).

  The `parser` can be either `(fn [query] resp)` or `(fn [env query] resp)`. The first is the default. If you want
  to use the latter then you must also pass `:parser-accepts-env? true` in the options map.

  The options map can include:

  * `:websockets-uri` (optional) - Defaults to /chsk
  * `:http-server-adapter` (required) - The sente server adapter to use.
  * `:transit-handlers` (optional) - Additional transit handlers for encoding/decoding data.
  * `:sente-options` (optional) - Additional options you want to send to the sente construction. (See Sente docs)
  * `:parser-accepts-env?` (optional, default false) - When true

  NOTE: If you supply a packer, you'll need to make sure tempids are supported (this is done by default, but if you override it, it is up to you.
  The default user id mapping is to use the internally generated UUID of the client. Use sente's `:user-id-fn` option
  to override this.

  When using a parser that accepts an env the parser environment will include:
    :websockets     The channel server component itself
    :push           A function that can send push messages to any connected client of this server. (just a shortcut to send-fn in websockets)
    :parser         The parser you gave this function
    :sente-message  The raw sente event.

  The websockets component must be joined into a real network server via a ring stack. The `wrap-api` function can be used to do that.
  "
  [parser {:keys [websockets-uri http-server-adapter transit-handlers sente-options parser-accepts-env?]}]
  (map->Websockets {:server-options      (merge {:user-id-fn (fn [r] (:client-id r))} sente-options)
                    :transit-handlers    (or transit-handlers {})
                    :websockets-uri      (or websockets-uri "/chsk")
                    :server-adapter      http-server-adapter
                    :parser-accepts-env? (boolean parser-accepts-env?)
                    :parser              parser}))

(defn wrap-api
  "Add API support to a Ring middleware chain. The websockets argument is an initialized Websockets component. Basically
  inject websockets into the component where you define your middleware, and (-> handler ... (wrap-api websockets) ...).

  NOTE: You must have wrap-keyword-params and wrap-params in the middleware chain!"
  [handler websockets]
  (let [{:keys [ring-ajax-post ring-ajax-get-or-ws-handshake websockets-uri]} websockets]
    (fn [{:keys [request-method uri] :as req}]
      (let [is-ws? (= websockets-uri uri)]
        (if is-ws?
          (case request-method
            :get (ring-ajax-get-or-ws-handshake req)
            :post (ring-ajax-post req))
          (handler req))))))
