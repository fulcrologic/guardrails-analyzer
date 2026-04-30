(ns com.fulcrologic.guardrails-analyzer.daemon.server.websockets-spec
  "Regression tests for daemon websockets defstate.

   Security regression: the `:csrf-token-fn` in the sente options of the
   websockets configuration was previously `nil`, which causes sente to fall
   back to a permissive default and leaves the websocket endpoint open to
   CSRF attacks. The fix installs a stable per-process CSRF token generator
   in the sente options.

   These tests pin that contract: the sente options must contain a non-nil,
   callable `:csrf-token-fn` that returns a stable, non-empty token."
  (:require
   [com.fulcrologic.guardrails-analyzer.daemon.server.websockets :as sut]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [com.fulcrologicpro.fulcro.networking.websocket-protocols :as wsp]
   [com.fulcrologicpro.fulcro.networking.websockets :as fws]
   [fulcro-spec.core :refer [assertions specification]]
   [mount.core :as mount]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn- capture-websockets-config!
  "Drive the `websockets` defstate :start body with all side-effecting
   collaborators stubbed, returning the configuration map that would have
   been handed to `fws/make-websockets` in production."
  []
  (let [captured (atom nil)
        stub-ws  (reify Object (toString [_] "stub-websockets"))]
    (with-redefs [fws/make-websockets (fn [_parser config]
                                        (reset! captured config)
                                        stub-ws)
                  fws/start!          (fn [ws] ws)
                  wsp/add-listener    (fn [_ws _listener] nil)]
      (try
        (mount/start #'sut/websockets)
        (finally
          (try (mount/stop #'sut/websockets) (catch Throwable _ nil)))))
    @captured))

(specification "websockets defstate :start passes a CSRF-protected sente config (security regression)"
               (let [config   (capture-websockets-config!)
                     sente    (:sente-options config)
                     token-fn (:csrf-token-fn sente)]
                 (assertions
                  "config map is captured when the defstate :start body runs"
                  (some? config) => true
                  "the sente options are present as a map"
                  (map? sente) => true
                  "the :csrf-token-fn key is present in :sente-options (regression: was missing/nil)"
                  (contains? sente :csrf-token-fn) => true
                  "the :csrf-token-fn value is non-nil (security fix)"
                  (some? token-fn) => true
                  "the :csrf-token-fn is callable as a function (sente will invoke it per request)"
                  (fn? token-fn) => true
                  "the :csrf-token-fn returns a non-nil token when invoked with a ring request"
                  (some? (token-fn {:headers {} :remote-addr "127.0.0.1"})) => true
                  "the :csrf-token-fn returns a string token"
                  (string? (token-fn {})) => true
                  "the :csrf-token-fn returns a non-empty token"
                  (pos? (count (token-fn {}))) => true
                  "the :csrf-token-fn returns the same token across calls (stable per-process token)"
                  (= (token-fn {:headers {}}) (token-fn {:headers {:foo "bar"}})) => true)))

(specification "websockets defstate :start configures parser-accepts-env? and an http server adapter"
               (let [config (capture-websockets-config!)]
                 (assertions
                  ":parser-accepts-env? is set to true so the pathom parser receives request env"
                  (:parser-accepts-env? config) => true
                  ":http-server-adapter is configured so sente can attach to http-kit"
                  (some? (:http-server-adapter config)) => true)))
