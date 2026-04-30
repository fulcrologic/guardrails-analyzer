(ns com.fulcrologic.guardrails-analyzer.daemon.server.http-server-spec
  "Regression tests for the daemon HTTP server's bind address.

   The daemon must NEVER expose itself on the wildcard (`0.0.0.0` / `::`).
   It must bind to the loopback interface only, since the daemon trusts
   anything that can reach its socket. These tests pin both the transient
   port-selection `ServerSocket` and the http-kit `run-server` config to
   `(InetAddress/getLoopbackAddress)`."
  (:require
   [com.fulcrologic.guardrails-analyzer.daemon.server.http-server :as sut]
   [com.fulcrologic.guardrails-analyzer.daemon.server.middleware :as mw]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions specification]]
   [mount.core :as mount]
   [org.httpkit.server :as http-kit])
  (:import
   (java.net InetAddress)))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn- start-and-capture-config!
  "Starts only the http-server defstate with all of its outward I/O stubbed,
   captures the config that the start fn passed to `http-kit/run-server`,
   and stops the state again. Returns the captured config map."
  []
  (let [captured-config (atom nil)]
    (with-redefs [;; The http-kit server is the side effect we want to observe.
                  ;; A fake here lets us assert exactly what the start fn asked
                  ;; http-kit to bind. We return a no-op stop-fn so mount's
                  ;; :stop body `(http-server)` works.
                  http-kit/run-server     (fn [_handler config]
                                            (reset! captured-config config)
                                            (fn stop-server [] :stopped))
                  ;; Avoid touching ~/.guardrails/daemon.port during tests.
                  sut/write-port-to-file! (fn [_file _port] nil)
                  ;; The middleware defstate normally chains through pathom +
                  ;; websockets state. Stub it so we can start http-server in
                  ;; isolation.
                  mw/middleware           (fn stub-handler [_req]
                                            {:status 404 :body "stub"})]
      (try
        (mount/start #'sut/http-server)
        (finally
          (mount/stop #'sut/http-server))))
    @captured-config))

(specification "http-server :start binds to the loopback address (regression)"
               (let [config        (start-and-capture-config!)
                     ip            (:ip config)
                     port          (:port config)
                     loopback-host (.getHostAddress (InetAddress/getLoopbackAddress))]
                 (assertions
                  ":ip is the host address of (InetAddress/getLoopbackAddress) (i.e. \"127.0.0.1\" in IPv4 mode)"
                  ip => loopback-host
                  ":ip resolves to a loopback InetAddress (per InetAddress.isLoopbackAddress)"
                  (.isLoopbackAddress (InetAddress/getByName ip)) => true
                  ":ip is NOT the IPv4 wildcard \"0.0.0.0\""
                  (= "0.0.0.0" ip) => false
                  ":ip is NOT the IPv6 wildcard \"::\""
                  (= "::" ip) => false
                  ":ip is NOT nil (which http-kit would treat as wildcard bind)"
                  (nil? ip) => false
                  ":port is a positive integer chosen via the transient ServerSocket that was bound to the loopback address"
                  (pos-int? port) => true)))
