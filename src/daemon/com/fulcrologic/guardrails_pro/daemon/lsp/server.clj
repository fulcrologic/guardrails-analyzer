(ns com.fulcrologic.guardrails-pro.daemon.lsp.server
  (:require
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
    (java.net ServerSocket)
    (java.util.concurrent CompletableFuture)))

(defonce client (atom nil))

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

(defn publish-diagnostics [client uri problems]
  (.publishDiagnostics client
    (new PublishDiagnosticsParams uri
      (mapv problem->diagnostic problems))))

(deftype LSPTextDocumentService []
  TextDocumentService
  (^void didOpen [_ ^DidOpenTextDocumentParams params]
    (let [document (.getTextDocument params)
          uri (.getUri document)]
      (log/info "didOpen:" uri)
      (when true
        (publish-diagnostics @client uri
          [#::grp.art{:problem-type :error/foobar
                      :message "AN ERROR mSG"
                      :line-start 1 :line-end 1
                      :column-start 0 :column-end 10}])))
    nil)
  (^void didChange [_ ^DidChangeTextDocumentParams params]
    (let [document (.getTextDocument params)
          uri (.getUri document)]
      (log/info "didChange:" uri))
    nil)
  (^void didSave [_ ^DidSaveTextDocumentParams params]
    (let [document (.getTextDocument params)
          uri (.getUri document)]
      (log/info "didSave:" uri))
    nil)
  (^void didClose [_ ^DidCloseTextDocumentParams params]
    (let [document (.getTextDocument params)
          uri (.getUri document)]
      (log/info "didClose:" uri))
    nil))

(def server
  (proxy [LanguageServer] []
    (^CompletableFuture initialize [^InitializeParams params]
      (log/info "initialize!")
      (CompletableFuture/completedFuture
        (new InitializeResult
          (doto (new ServerCapabilities)
            (.setTextDocumentSync
              (doto (new TextDocumentSyncOptions)
                (.setOpenClose true)))))))
    (^void initialized [^InitializedParams params]
      (log/info "initialized!"))
    (^CompletableFuture shutdown []
      (log/info "shutdown!")
      (CompletableFuture/completedFuture 0))
    (^void exit []
      (log/info "exit!"))
    (getTextDocumentService []
      (new LSPTextDocumentService))
    (getWorkspaceService []
      (new LSPWorkspaceService))))

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

(defn start-lsp []
  (log/info "Starting guardrails LSP server!")
  (let [server-socket (new ServerSocket 0)
        port-file (find-port-file ".")]
    (.deleteOnExit port-file)
    (write-port-to-file! port-file
      (.getLocalPort server-socket))
    (let [socket (.accept server-socket)
          launcher (LSPLauncher/createServerLauncher server
                     (.getInputStream socket)
                     (.getOutputStream socket))]
      (reset! client (.getRemoteProxy launcher))
      (.startListening launcher)
      {::client client
       ::port-file port-file
       ::server server
       ::server-socket server-socket
       ::socket socket})))

(defn stop-lsp [{::keys [port-file server socket server-socket]} ]
  (log/info "Stopping guardrails LSP server!")
  (.delete port-file)
  (.shutdownInput socket)
  (.shutdownOutput socket)
  @(.shutdown server)
  (.exit server)
  (.close socket)
  (.close server-socket))
