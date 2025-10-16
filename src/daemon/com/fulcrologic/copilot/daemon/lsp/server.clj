(ns com.fulcrologic.copilot.daemon.lsp.server
  (:require
    [clojure.core.async :as async]
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [com.fulcrologic.copilot.daemon.lsp.commands :as lsp.cmds]
    [com.fulcrologic.copilot.daemon.lsp.diagnostics :as lsp.diag]
    [com.fulcrologicpro.taoensso.timbre :as log])
  (:import
    (java.net ServerSocket SocketException SocketTimeoutException)
    (java.util UUID)
    (java.util.concurrent CompletableFuture)
    (org.eclipse.lsp4j
      DidChangeConfigurationParams
      DidChangeTextDocumentParams
      DidChangeWatchedFilesParams
      DidCloseTextDocumentParams
      DidOpenTextDocumentParams
      DidSaveTextDocumentParams
      ExecuteCommandOptions
      ExecuteCommandParams
      InitializeParams
      InitializeResult
      InitializedParams
      ServerCapabilities
      TextDocumentSyncOptions)
    (org.eclipse.lsp4j.launch LSPLauncher)
    (org.eclipse.lsp4j.services LanguageServer TextDocumentService WorkspaceService)))

(defn read-json [x]
  (json/read-str (str x) :key-fn keyword))

(deftype LSPWorkspaceService [client-id-atom]
  WorkspaceService
  (^CompletableFuture executeCommand [_ ^ExecuteCommandParams params]
    (let [cmd  (.getCommand params)
          args (.getArguments params)]
      (log/info "executeCommand" cmd "&" args)
      (if-let [f (get lsp.cmds/commands cmd)]
        (apply f @client-id-atom (map read-json args))
        (log/warn "Unrecognized command:" cmd)))
    (CompletableFuture/completedFuture 0))
  (^void didChangeConfiguration [_ ^DidChangeConfigurationParams params]
    (log/info "didChangeConfiguration:" params)
    nil)
  (^void didChangeWatchedFiles [_ ^DidChangeWatchedFilesParams params]
    (log/info "didChangeWatchedFiles:" params)
    nil))

(deftype LSPTextDocumentService [client-id-atom]
  TextDocumentService
  (^void didOpen [_ ^DidOpenTextDocumentParams params]
    (let [document (.getTextDocument params)
          uri      (.getUri document)]
      (swap! lsp.diag/client-id->open-uri assoc @client-id-atom uri))
    nil)
  (^void didClose [_ ^DidCloseTextDocumentParams params]
    (let [document (.getTextDocument params)
          uri      (.getUri document)]
      (swap! lsp.diag/client-id->open-uri dissoc @client-id-atom))
    nil)
  (^void didChange [_ ^DidChangeTextDocumentParams params] nil)
  (^void didSave [_ ^DidSaveTextDocumentParams params] nil))

(defn new-server [launcher]
  (let [client-id (atom nil)]
    (proxy [LanguageServer] []
      (^CompletableFuture initialize [^InitializeParams params]
        (let [id (UUID/randomUUID)]
          (log/info "initialize server!" {:id id})
          (swap! lsp.diag/client:id->info
            assoc id {:remote      (.getRemoteProxy @launcher)
                      :project-dir (.getRootPath params)})
          (reset! client-id id))
        (CompletableFuture/completedFuture
          (new InitializeResult
            (doto (new ServerCapabilities)
              (.setTextDocumentSync
                (doto (new TextDocumentSyncOptions)
                  (.setOpenClose true)))
              (.setExecuteCommandProvider
                (doto (new ExecuteCommandOptions)
                  (.setCommands (keys lsp.cmds/commands))))))))
      (^void initialized [^InitializedParams params]
        (log/info "client initialized!"))
      (^CompletableFuture shutdown []
        (log/info "shutdown!" @client-id)
        (swap! lsp.diag/client:id->info dissoc @client-id)
        (reset! client-id nil)
        (CompletableFuture/completedFuture 0))
      (^void exit []
        (log/info "exit!"))
      (getTextDocumentService []
        (new LSPTextDocumentService client-id))
      (getWorkspaceService []
        (new LSPWorkspaceService client-id)))))

(def port-file
  (io/file (System/getProperty "user.home")
    ".copilot/lsp-server.port"))

(defn write-port-to-file! [file port]
  (io/make-parents file)
  (spit file port))

(defn- shutdown-socket [s]
  (when-not (.isInputShutdown s)
    (.shutdownInput s))
  (when-not (.isOutputShutdown s)
    (.shutdownOutput s)))

(defn start-lsp []
  (let [server-socket (doto (new ServerSocket 0)
                        (.setSoTimeout 1000))
        port          (.getLocalPort server-socket)]
    (log/info "Starting Copilot Daemon LSP server on:" port)
    (.deleteOnExit port-file)
    (write-port-to-file! port-file port)
    (let [sockets   (atom [])
          stop-chan (async/chan)]
      (async/go-loop []
        (if (= ::STOP (async/alt!
                        stop-chan ([v] v)
                        (async/timeout 1000) nil))
          (do (log/info "Received stop signal, shutting down copilot LSP server!")
              (.close server-socket)
              (reset! lsp.diag/client:id->info {})
              (doseq [s @sockets]
                (shutdown-socket s))
              (reset! sockets []))
          (do (when-let [socket (try (.accept server-socket)
                                     (catch SocketTimeoutException _ nil)
                                     (catch SocketException e
                                       (log/error e "socket exception")
                                       nil))]
                (let [launcher (atom nil)
                      server   (new-server launcher)]
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

(defn stop-lsp [{::keys [port-file stop-chan]}]
  (log/info "Stopping copilot LSP server!")
  (.delete port-file)
  (async/put! stop-chan ::STOP))
