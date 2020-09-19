(ns com.fulcrologic.guardrails-pro.daemon.lsp.server
  (:require
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

(deftype LSPTextDocumentService []
  TextDocumentService
  (^void didOpen [_ ^DidOpenTextDocumentParams params]
    (let [document (.getTextDocument params)
          uri (.getUri document)]
      (log/info "didOpen:" uri)
      (when false
        (.publishDiagnostics @client
          (new PublishDiagnosticsParams uri
            [(new Diagnostic
               (new Range
                 (new Position 1 1)
                 (new Position 1 2))
               "Work in Progress"
               DiagnosticSeverity/Information
               "guardrails")]))))
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

(defn start-lsp [port]
  (log/info "Starting guardrails LSP server!")
  (let [server-socket (new ServerSocket port)
        socket (.accept server-socket)
        launcher (LSPLauncher/createServerLauncher server
                   (.getInputStream socket)
                   (.getOutputStream socket))]
    (reset! client (.getRemoteProxy launcher))
    (.startListening launcher)
    {::client client
     ::server server
     ::server-socket server-socket
     ::socket socket}))

(defn stop-lsp [{::keys [server socket server-socket]} ]
  (log/info "Stopping guardrails LSP server!")
  (.shutdownInput socket)
  (.shutdownOutput socket)
  @(.shutdown server)
  (.exit server)
  (.close socket)
  (.close server-socket))
