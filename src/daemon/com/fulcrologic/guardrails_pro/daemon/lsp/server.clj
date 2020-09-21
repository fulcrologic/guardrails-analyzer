(ns com.fulcrologic.guardrails-pro.daemon.lsp.server
  (:require
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [taoensso.timbre :as log])
  (:import
    (org.eclipse.lsp4j
      Diagnostic
      DiagnosticSeverity
      DidChangeConfigurationParams
      DidChangeTextDocumentParams
      DidChangeWatchedFilesParams
      DidCloseTextDocumentParams
      DidOpenTextDocumentParams
      DidSaveTextDocumentParams
      InitializeParams
      InitializeResult
      InitializedParams
      Position
      PublishDiagnosticsParams
      Range
      ServerCapabilities
      TextDocumentSyncOptions)
    (org.eclipse.lsp4j.launch LSPLauncher)
    (org.eclipse.lsp4j.services LanguageServer TextDocumentService WorkspaceService)
    (java.net ServerSocket SocketException SocketTimeoutException)
    (java.util UUID)
    (java.util.concurrent CompletableFuture)))

(defonce clients (atom {}))

(deftype LSPWorkspaceService []
  WorkspaceService
  (^void didChangeConfiguration [_ ^DidChangeConfigurationParams params]
    (log/info "didChangeConfiguration:" params)
    nil)
  (^void didChangeWatchedFiles [_ ^DidChangeWatchedFilesParams params]
    (log/info "didChangeWatchedFiles:" params)
    nil))

(defn problem->diagnostic
  [{::grp.art/keys [problem-type message
                    line-start line-end
                    column-start column-end]}]
  (new Diagnostic
    (new Range
      (new Position line-start column-start)
      (new Position line-end   column-end))
    message
    (case (namespace problem-type)
      "error"   DiagnosticSeverity/Error
      "warning" DiagnosticSeverity/Warning
      "info"    DiagnosticSeverity/Information
      "hint"    DiagnosticSeverity/Hint
      DiagnosticSeverity/Error)
    "guardrails-pro"))

(defn publish-diagnostics [uri problems]
  (doseq [[_ client] @clients]
    (.publishDiagnostics client
      (new PublishDiagnosticsParams uri
        (mapv problem->diagnostic problems)))))

(deftype LSPTextDocumentService []
  TextDocumentService
  (^void didOpen [_ ^DidOpenTextDocumentParams params]
    (let [document (.getTextDocument params)
          uri (.getUri document)]
      (log/info "didOpen:" uri)
      (publish-diagnostics uri
        [#::grp.art{:problem-type :error/foobar
                    :message "AN ERROR mSG"
                    :line-start 1 :line-end 1
                    :column-start 0 :column-end 10}]))
    nil)
  (^void didChange [_ ^DidChangeTextDocumentParams params]
    (let [document (.getTextDocument params)
          uri (.getUri document)]
      (log/info "didChange:" uri)
      (publish-diagnostics uri
        [#::grp.art{:problem-type :error/foobar
                    :message "AN ERROR mSG"
                    :line-start 1 :line-end 1
                    :column-start 0 :column-end 10}]) )
    nil)
  (^void didSave [_ ^DidSaveTextDocumentParams params]
    (let [document (.getTextDocument params)
          uri (.getUri document)]
      (log/info "didSave:" uri)
      (publish-diagnostics uri
        [#::grp.art{:problem-type :error/foobar
                    :message "AN ERROR mSG"
                    :line-start 1 :line-end 1
                    :column-start 0 :column-end 10}]))
    nil)
  (^void didClose [_ ^DidCloseTextDocumentParams params]
    (let [document (.getTextDocument params)
          uri (.getUri document)]
      (log/info "didClose:" uri))
    nil))

(defn new-server [launcher]
  (let [client-id (atom nil)]
    (proxy [LanguageServer] []
      (^CompletableFuture initialize [^InitializeParams params]
        (let [id (UUID/randomUUID)]
          (log/info "initialize server!" {:id id})
          (swap! clients assoc id (.getRemoteProxy @launcher))
          (reset! client-id id))
        (CompletableFuture/completedFuture
          (new InitializeResult
            (doto (new ServerCapabilities)
              (.setTextDocumentSync
                (doto (new TextDocumentSyncOptions)
                  (.setOpenClose true)))))))
      (^void initialized [^InitializedParams params]
        (log/info "client initialized!"))
      (^CompletableFuture shutdown []
        (log/info "shutdown!" @client-id)
        (swap! clients dissoc @client-id)
        (reset! client-id nil)
        (CompletableFuture/completedFuture 0))
      (^void exit []
        (log/info "exit!"))
      (getTextDocumentService []
        (new LSPTextDocumentService))
      (getWorkspaceService []
        (new LSPWorkspaceService)))))

(defn find-port-file
  [^java.io.File start-dir]
  (loop [dir start-dir]
    (let [config-file (io/file dir "guardrails.edn")]
      (if (.exists config-file)
        (io/file dir ".guardrails-pro" "daemon.port")
        (if-let [parent (.getParentFile dir)]
          (recur parent)
          (throw (ex-info "Failed to find project configuration!"
                   {:start-dir start-dir})))))))

(defn write-port-to-file! [file port]
  (io/make-parents file)
  (spit file port))

(defn- shutdown-socket [s]
  (when-not (.isInputShutdown s)
    (.shutdownInput s))
  (when-not (.isOutputShutdown s)
    (.shutdownOutput s)))

(defn start-lsp []
  (log/info "Starting guardrails LSP server!")
  (let [server-socket (doto (new ServerSocket 0)
                        (.setSoTimeout 1000))
        port-file (find-port-file ".")]
    (.deleteOnExit port-file)
    (write-port-to-file! port-file
      (.getLocalPort server-socket))
    (let [sockets (atom [])
          stop-chan (async/chan)]
      (async/go-loop []
        (if (= ::STOP (async/alt!
                        stop-chan ([v] v)
                        (async/timeout 1000) nil))
          (do (log/info "Received stop signal, shutting down guardrails LSP server!")
            (.close server-socket)
            (reset! clients {})
            (doseq [s @sockets]
              (shutdown-socket s))
            (reset! sockets []))
          (do (when-let [socket (try (.accept server-socket)
                                  (catch SocketTimeoutException _ nil)
                                  (catch SocketException e
                                    (log/error e "socket exception")
                                    nil))]
                (let [launcher (atom nil)
                      server (new-server launcher)]
                  (reset! launcher
                    (LSPLauncher/createServerLauncher server
                      (.getInputStream socket)
                      (.getOutputStream socket)))
                  (swap! sockets conj socket)
                  (async/go-loop [F (.startListening @launcher)]
                    (if (future-done? F)
                      (do (log/debug "future-done!")
                        (shutdown-socket socket)
                        @(.shutdown server)
                        (.close socket))
                      (recur F)))))
            (recur))))
      {::port-file port-file
       ::stop-chan stop-chan})))

(defn stop-lsp [{::keys [port-file stop-chan]} ]
  (log/info "Stopping guardrails LSP server!")
  (.delete port-file)
  (async/put! stop-chan ::STOP))
