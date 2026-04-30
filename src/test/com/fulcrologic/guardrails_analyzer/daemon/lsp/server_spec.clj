(ns com.fulcrologic.guardrails-analyzer.daemon.lsp.server-spec
  "Regression tests for LSP server bind-address and executeCommand whitelist.

   The bind-address regression is now a behavior-level test: `start-lsp`
   is invoked on an ephemeral port (the production code already passes 0
   to `ServerSocket`), the resulting server-socket is probed for
   reachability on the loopback interface, and unreachability on any
   non-loopback interface. Touching the user's home directory is avoided
   by redefining `port-file` to a temp file and capturing the bound port
   via a `write-port-to-file!` redefinition.

   The executeCommand whitelist test exercises `LSPWorkspaceService`
   directly: an unknown command must NOT dispatch to anything in
   `lsp.cmds/commands` and must still return a completed CompletableFuture."
  (:require
   [com.fulcrologic.guardrails-analyzer.daemon.lsp.commands :as lsp.cmds]
   [com.fulcrologic.guardrails-analyzer.daemon.lsp.server :as sut]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions component specification]])
  (:import
   (java.io File)
   (java.net ConnectException Inet4Address InetAddress InetSocketAddress NetworkInterface Socket SocketTimeoutException)
   (java.util.concurrent CompletableFuture)
   (org.eclipse.lsp4j ExecuteCommandParams)))

(tf/use-fixtures :once tf/with-default-test-logging-config)

;; ============================================================================
;; Bind-address regression — behavior-level assertion
;; ============================================================================

(defn- first-non-loopback-ipv4
  "Returns the first IPv4 `InetAddress` belonging to an UP non-loopback
   `NetworkInterface`, or nil if the host has none. Used as a probe address
   for verifying that the LSP server is NOT reachable on any non-loopback
   interface."
  ^InetAddress []
  (->> (NetworkInterface/getNetworkInterfaces)
       enumeration-seq
       (filter (fn [^NetworkInterface ni]
                 (and (.isUp ni) (not (.isLoopback ni)))))
       (mapcat (fn [^NetworkInterface ni]
                 (enumeration-seq (.getInetAddresses ni))))
       (filter #(instance? Inet4Address %))
       first))

(defn- probe-connect
  "Attempts to open a `Socket` to `host:port` with a short timeout.
   Returns one of `:connected`, `:refused`, `:timeout`, or `:error`.
   Used to behaviorally verify that the LSP `ServerSocket` is bound to
   loopback only (probing a non-loopback interface should NOT connect)."
  [^InetAddress host ^long port]
  (let [s (Socket.)]
    (try
      (.connect s (InetSocketAddress. host (int port)) 500)
      :connected
      (catch ConnectException _ :refused)
      (catch SocketTimeoutException _ :timeout)
      (catch Exception _ :error)
      (finally
        (try (.close s) (catch Exception _))))))

(specification "start-lsp binds the LSP ServerSocket to loopback only (regression)"
               (let [tmp-port-file (doto (File/createTempFile "lsp-port-test" ".port")
                                     (.deleteOnExit))
                     captured-port (atom nil)]
                 (with-redefs [sut/port-file           tmp-port-file
                               sut/write-port-to-file! (fn [_file port] (reset! captured-port port))]
                   (let [server (sut/start-lsp)]
                     (try
                       (let [port (int @captured-port)]
                         (assertions
                          "captured an ephemeral port (the server-socket actually bound)"
                          (pos-int? port) => true)

                         (component "loopback reachability"
                                    (let [loopback (InetAddress/getLoopbackAddress)
                                          s        (Socket. loopback port)]
                                      (try
                                        (assertions
                                         "a client connecting to loopback is connected"
                                         (.isConnected s) => true

                                         "the connected remote address is a loopback address"
                                         (.. s getInetAddress isLoopbackAddress) => true)
                                        (finally (.close s)))))

                         (component "non-loopback unreachability"
                                    (let [non-lb (first-non-loopback-ipv4)]
                                      (if (nil? non-lb)
                                        (assertions
                                         "host has no non-loopback IPv4 interface; probe skipped"
                                         true => true)
                                        (assertions
                                         "a client connecting via a non-loopback interface is refused or times out (server is NOT bound to wildcard)"
                                         (probe-connect non-lb port) =fn=> #{:refused :timeout})))))
                       (finally
                         (sut/stop-lsp server)))))))

;; ============================================================================
;; executeCommand whitelist — direct deftype dispatch
;; ============================================================================

(defn- ->params
  "Build an `ExecuteCommandParams` instance with `cmd` and the (already
   JSON-encoded) string `args`."
  ^ExecuteCommandParams [^String cmd args]
  (doto (ExecuteCommandParams.)
    (.setCommand cmd)
    (.setArguments (vec args))))

(specification "LSPWorkspaceService.executeCommand whitelists known commands"
               (component "unknown command is rejected"
                          (let [client-id #uuid "00000000-0000-0000-0000-000000000001"
                                service   (sut/->LSPWorkspaceService (atom client-id))
                                calls     (atom [])]
                            (with-redefs [lsp.cmds/check-file!      (fn [& args]
                                                                      (swap! calls conj
                                                                             {:fn :check-file! :args args}))
                                          lsp.cmds/check-root-form! (fn [& args]
                                                                      (swap! calls conj
                                                                             {:fn :check-root-form! :args args}))]
                              (let [result (.executeCommand service (->params "evil-command" []))]
                                (assertions
                                 "returns a CompletableFuture"
                                 (instance? CompletableFuture result) => true
                                 "future completes with 0 (does not propagate the unknown command)"
                                 (.get result) => 0
                                 "no whitelisted command function is invoked for an unknown command"
                                 @calls => [])))))

               (component "another unknown name with arguments is still rejected"
                          (let [service (sut/->LSPWorkspaceService (atom #uuid "00000000-0000-0000-0000-0000000000aa"))
                                calls   (atom [])]
                            (with-redefs [lsp.cmds/check-file!      (fn [& args]
                                                                      (swap! calls conj :check-file!))
                                          lsp.cmds/check-root-form! (fn [& args]
                                                                      (swap! calls conj :check-root-form!))]
        ;; Note: arguments are JSON-encoded strings (the LSP wire format).
        ;; Even with plausible-looking args, an unknown command must not dispatch.
                              (.executeCommand service (->params "shell-exec" ["\"rm -rf /\""]))
                              (assertions
                               "no whitelisted command function is invoked"
                               @calls => []))))

               (component "known command 'check-file!' dispatches to lsp.cmds/check-file!"
                          (let [client-id #uuid "00000000-0000-0000-0000-000000000002"
                                service   (sut/->LSPWorkspaceService (atom client-id))
                                calls     (atom [])
                                stub-cf   (fn [& args]
                                            (swap! calls conj
                                                   {:fn :check-file! :args (vec args)}))
                                stub-crf  (fn [& args]
                                            (swap! calls conj
                                                   {:fn :check-root-form! :args (vec args)}))]
                            (with-redefs [lsp.cmds/check-file!      stub-cf
                                          lsp.cmds/check-root-form! stub-crf
                                          lsp.cmds/commands         {"check-file!"      stub-cf
                                                                     "check-root-form!" stub-crf}]
                              (let [result (.executeCommand service
                                                            (->params "check-file!" ["\"/tmp/foo.clj\"" "{}"]))]
                                (assertions
                                 "future completes with 0"
                                 (.get result) => 0
                                 "lsp.cmds/check-file! invoked exactly once"
                                 (count @calls) => 1
                                 "dispatched to check-file! (not check-root-form!)"
                                 (:fn (first @calls)) => :check-file!
                                 "client-id is forwarded as the first argument"
                                 (first (:args (first @calls))) => client-id
                                 "JSON arguments are decoded before dispatch (path arg)"
                                 (second (:args (first @calls))) => "/tmp/foo.clj")))))

               (component "known command 'check-root-form!' dispatches to lsp.cmds/check-root-form!"
                          (let [client-id #uuid "00000000-0000-0000-0000-000000000003"
                                service   (sut/->LSPWorkspaceService (atom client-id))
                                calls     (atom [])
                                stub-cf   (fn [& args]
                                            (swap! calls conj
                                                   {:fn :check-file! :args (vec args)}))
                                stub-crf  (fn [& args]
                                            (swap! calls conj
                                                   {:fn :check-root-form! :args (vec args)}))]
                            (with-redefs [lsp.cmds/check-file!      stub-cf
                                          lsp.cmds/check-root-form! stub-crf
                                          lsp.cmds/commands         {"check-file!"      stub-cf
                                                                     "check-root-form!" stub-crf}]
                              (.executeCommand service
                                               (->params "check-root-form!" ["\"/tmp/foo.clj\"" "42" "{}"]))
                              (assertions
                               "lsp.cmds/check-root-form! invoked exactly once"
                               (count @calls) => 1
                               "dispatched to check-root-form! (not check-file!)"
                               (:fn (first @calls)) => :check-root-form!
                               "client-id is forwarded as the first argument"
                               (first (:args (first @calls))) => client-id
                               "JSON line argument is decoded as a number"
                               (nth (:args (first @calls)) 2) => 42)))))

;; ============================================================================
;; Whitelist contents — protect against silent additions
;; ============================================================================

(specification "lsp.cmds/commands whitelist is exactly the known set"
               (assertions
                "only the two whitelisted commands are exposed to executeCommand"
                (set (keys lsp.cmds/commands)) => #{"check-file!" "check-root-form!"}

                "every whitelist entry is a callable function"
                (every? fn? (vals lsp.cmds/commands)) => true))
