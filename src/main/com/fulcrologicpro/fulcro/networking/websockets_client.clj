(ns com.fulcrologicpro.fulcro.networking.websockets-client
  (:require
    [clojure.core.async :as async :refer [go go-loop]]
    [com.fulcrologicpro.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologicpro.fulcro.networking.transit-packer :as tp]
    [com.fulcrologicpro.taoensso.sente :as sente]
    [com.fulcrologicpro.taoensso.timbre :as log]
    [com.fulcrologicpro.fulcro.algorithms.do-not-use :as futil])
  (:import (clojure.lang Atom)))

(defn- make-event-handler
  "Probably need to make it possible for extension from outside."
  [push-handler]
  (fn [{:keys [id ?data]}]
    (case id
      :api/server-push (when push-handler (push-handler ?data))
      nil)))

(defn- network-request-failed!
  [{::keys [options] :as remote} handler]
  (let [body {:fulcro.server/error :network-disconnect}
        {:keys [global-error-callback]} options]
    (handler {:status-code 408 :body body})
    (when global-error-callback
      (global-error-callback {:status 408 :body body}))))

(defn cb-error? [cb-reply-clj] (#{:chsk/closed :chsk/timeout :chsk/error} cb-reply-clj))
(defn cb-success? [cb-reply-clj] (not (cb-error? cb-reply-clj)))

(defn- start!
  "Starts network operations on the remote. Will be automatically triggered if network communications are attempted on the
  remote when it is in a stopped state."
  [{::keys [fwstate options send!] :as remote}]
  (if (-> fwstate deref :started?)
    (log/error "Attempt to start already started websocket remote")
    (let [{:keys [websockets-uri global-error-callback push-handler host req-params
                  state-callback transit-handlers auto-retry? sente-options
                  csrf-token request-timeout-ms]
           :or   {request-timeout-ms 30000}} options
          websockets-uri   (or websockets-uri "/chsk")
          csrf-token       (or csrf-token "NO CSRF TOKEN SUPPLIED")
          queue            (:queue @fwstate)
          {:keys [ch-recv state send-fn] :as cs} (sente/make-channel-socket-client!
                                                   websockets-uri ; path on server
                                                   csrf-token
                                                   (merge {:packer         (tp/make-packer transit-handlers)
                                                           :host           host
                                                           :type           :ws
                                                           :backoff-ms-fn  (fn [attempt] (min (* attempt 1000) 4000))
                                                           :params         req-params
                                                           :wrap-recv-evs? false}
                                                     sente-options))
          message-received (make-event-handler push-handler)]
      (when-not (pos-int? request-timeout-ms)
        (throw (ex-info "Request timeout must be a positive integer." {})))
      (log/debug "Starting websocket remote")
      (swap! fwstate assoc :started? true)
      (when-not auto-retry?
        (add-watch state ::cancel-in-flight (fn [_ _ {was-open? :open?} {is-open? :open?}]
                                              (let [{::keys [in-flight-handler]} @fwstate]
                                                (when (and in-flight-handler was-open? (not is-open?))
                                                  (log/error "Connection dropped. Notifying in-flight handler that the request will not finish")
                                                  (network-request-failed! remote in-flight-handler))))))
      (add-watch state ::ready (fn [_ _ _ n]
                                 (if auto-retry?
                                   (do
                                     ;; prevent send attempts until open again.
                                     (swap! fwstate assoc :ready? (:open? n)))
                                   ;; not auto-retry: so just bring it up the first time and essentially ignore updates.
                                   (when (:open? n)
                                     (swap! fwstate assoc :ready? true)))))
      (cond
        (fn? state-callback) (add-watch state ::state-callback (fn [_ _ o n] (state-callback o n)))
        (instance? Atom state-callback) (add-watch state ::state-callback (fn [_ _ o n] (@state-callback o n))))
      (swap! fwstate assoc :channel-socket cs)
      (swap! fwstate assoc :stop-chsk-router! (sente/start-client-chsk-router! ch-recv message-received))

      (log/debug "Starting request processing loop with overall timeout of " request-timeout-ms "ms."
        (str "(" (/ request-timeout-ms 60000.0) " minutes)"))
      (async/go-loop []
        (if (some-> fwstate deref :ready?)
          (let [{:keys [edn handler]} (async/<! queue)]
            (try
              (swap! fwstate assoc ::in-flight-handler handler)
              (send-fn [:fulcro.client/API edn] request-timeout-ms
                (fn process-response [resp]
                  (swap! fwstate dissoc ::in-flight-handler)
                  (cond
                    (cb-success? resp) (let [{:keys [status body]} resp]
                                         (handler {:status-code status
                                                   :body        body})
                                         (when (and (not= 200 status) global-error-callback)
                                           (global-error-callback resp)))
                    (= :chsk/timeout resp) (let [result {:status-code 408
                                                         :body        "Request timed out."}]
                                             (log/error "websocket processing timeout! Request failed.")
                                             (handler result)
                                             (when global-error-callback (global-error-callback result)))
                    :else (network-request-failed! remote handler))))
              (catch :default e
                (log/error "Sente send failure!" e))))
          (do
            (log/info "Send attempted before channel ready...waiting")
            (async/<! (async/timeout 1000))))
        (if (:started? @fwstate)
          (recur)
          (log/info "Websocket processing loop exited."))))))

(defn stop!
  "Disconnects the websocket. The remote will automatically restart and reconnect if the client attempts to use the
   remote again.  This can be used in applications where the client is taken off screen and you need to reclaim the
   networking resource, but the client might be re-added to the screen and will need to resume operation.

   NOTES: Stopping a remote will drain the Fulcro queue by giving a hard response to every request in the queue."
  [{::keys [fwstate] :as remote}]
  (let [{:keys [queue stop-chsk-router! channel-socket]} @fwstate
        {:keys [chsk]} channel-socket]
    (swap! fwstate (fn [s]
                     (-> s
                       (assoc :started? false :ready? false)
                       (dissoc :stop-chsk-router! :channel-socket))))
    ;; drain the queue
    (async/go-loop []
      (let [{:keys [edn handler] :as request} (async/poll! queue)]
        (when request
          (network-request-failed! remote handler)
          (recur))))
    (log/debug "Stopping Sente router")
    (stop-chsk-router!)
    (log/debug "Disconnecting network")
    (sente/chsk-disconnect! chsk)))

(defn fulcro-websocket-remote
  "Creates a websocket-based Fulcro remote. Requires you add the corresponding Fulrcro websocket middleware to your
  server.

   Params map can contain:

   - `websockets-uri` - (optional) The uri to handle websocket traffic on. (ex. \"/chsk\", which is the default value)
   - `push-handler` - (optional) A function (fn [{:keys [topic msg]}] ...) that can handle a push message.
                      The topic is the server push verb, and the message will be the EDN sent.
   - `host` - (optional) Host option to send to sente
   - `req-params` - (optional) Params for sente socket creation
   - `transit-handlers` - (optional) A map with optional :read and :write keys that given added sente packer.
   - `state-callback` (optional) - Callback that runs when the websocket state of the websocket changes.
                                   The function takes an old state parameter and a new state parameter (arity 2 function).
                                   `state-callback` can be either a function, or an atom containing a function.
   - `global-error-callback` - (optional) A function (fn [resp] ...) that is called when returned status code from the server is not 200.
   - `auto-retry?` - A boolean (default false). If set to true any network disconnects will lead to infinite retries until
                     the network returns. All remote mutations should be idempotent.
   - `sente-options` - (optional) A map of options that is passed directly to the sente websocket channel construction (see sente docs).
   - `csrf-token` - (optional) The CSRF token provided by the server (embedded in HTML. See Dev Guide).
   - `request-timeout-ms` - (optional) Number of ms to wait for a response from an API request. Defaults to 30000.
   - `delay-start?` - (optional) Avoids establishing a connection until there is a real request. Defaults to false.
   "
  [{:keys [websockets-uri global-error-callback push-handler host req-params
           state-callback transit-handlers auto-retry? sente-options delay-start?
           csrf-token request-timeout-ms]
    :or   {request-timeout-ms 30000}
    :as   options}]
  (let [queue     (async/chan)
        send!     (fn send* [edn result-handler] (async/go (async/>! queue {:edn edn :handler result-handler})))
        transmit! (fn transmit*! [the-remote {::txn/keys [ast result-handler] :as req}]
                    ;; Don't start the processing until we see a real request. Prevents loaded but inactive clients
                    ;; from allocating network resources. Primarily an issue with things like workspaces that load the
                    ;; clients for many many apps, but only mount a few.
                    (when-not (-> the-remote ::fwstate deref :started?)
                      (start! the-remote))
                    (let [edn (futil/ast->query ast)]
                      (send! edn result-handler)))
        fwstate   (atom
                    {:channel-socket nil
                     :queue          queue
                     :started?       false
                     :ready?         false
                     :auto-retry?    auto-retry?})
        remote    {::fwstate  fwstate
                   ::options  options
                   ::send!    send!
                   :transmit! transmit!}]
    (when-not delay-start?
      (start! remote))
    remote))

(defn reconnect!
  "Request that the given websockets networking component disconnect/reconnect.  Useful
  after login to ensure updated cookies are present on the request that re-establishes the websocket."
  [websockets]
  (let [chsk (some-> websockets ::fwstate deref :channel-socket :chsk)]
    (sente/chsk-reconnect! chsk)))
