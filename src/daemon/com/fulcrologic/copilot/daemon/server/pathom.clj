(ns com.fulcrologic.copilot.daemon.server.pathom
  (:require
    [com.fulcrologic.copilot.daemon.lsp.diagnostics :as lsp.diag]
    [com.fulcrologic.copilot.daemon.server.bindings :as bindings]
    [com.fulcrologic.copilot.daemon.server.checkers :as daemon.check]
    [com.fulcrologic.copilot.daemon.server.config :refer [config]]
    [com.fulcrologic.copilot.daemon.server.connection-management :as cmgmt]
    [com.fulcrologic.copilot.daemon.server.problems :as problems]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [mount.core :refer [defstate]]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(pc/defresolver all-problems [_env _params]
  {::pc/output [:all-problems]}
  {:all-problems (problems/get!)})

(pc/defmutation report-error [{:keys [websockets]} {:keys [error]}]
  {::pc/sym 'daemon/report-error}
  (cmgmt/report-error! websockets error)
  (lsp.diag/report-error! error)
  {})

(pc/defmutation report-analysis [{:keys [websockets]} {:keys [bindings problems]}]
  {::pc/sym    'daemon/report-analysis
   ::pc/output [:result]}
  (problems/set! problems)
  (bindings/set! bindings)
  (cmgmt/update-viewers! websockets)
  (lsp.diag/update-problems! problems)
  {})

(pc/defmutation subscribe [{:keys [websockets cid]} viewer-info]
  {::pc/sym 'daemon/subscribe}
  (log/info "Client subscribed to error updates: " cid)
  (swap! cmgmt/subscribed-viewers assoc cid viewer-info)
  (cmgmt/update-viewers! websockets [cid viewer-info])
  {})

(pc/defmutation register-checker [{:keys [cid]} checker-info]
  {::pc/sym 'daemon/register-checker}
  (log/info "Checker registered: " cid)
  (swap! cmgmt/registered-checkers assoc cid checker-info)
  {})

(pc/defmutation check-current-file [{:keys [websockets]} {:keys [file opts]}]
  {::pc/sym 'daemon/check-current-file}
  (daemon.check/check-file! websockets file opts)
  {})

(pc/defmutation check-root-form [{:keys [websockets]} {:keys [file line opts]}]
  {::pc/sym 'daemon/check-root-form}
  (daemon.check/check-root-form! websockets file line opts)
  {})

(def all-resolvers [all-problems report-analysis report-error
                    subscribe register-checker
                    check-current-file check-root-form])

(defn preprocess-parser-plugin [f]
  {::p/wrap-parser
   (fn [parser]
     (fn [env tx]
       (let [{:keys [env tx]} (f {:env env :tx tx})]
         (if (and (map? env) (seq tx))
           (parser env tx)
           {}))))})

(defn log-requests [req]
  (log/debug "Pathom transaction:" (pr-str (:tx req)))
  req)

(defn log-error [env e]
  (log/error e "Error handling pathom request:")
  (p/error-str e))

(defn build-parser []
  (let [real-parser (p/parser
                      {::p/mutate  pc/mutate
                       ::p/env     {::p/reader               [p/map-reader
                                                              pc/reader2
                                                              pc/open-ident-reader
                                                              p/env-placeholder-reader]
                                    ::p/placeholder-prefixes #{">"}
                                    ::p/process-error        log-error
                                    :config                  config}
                       ::p/plugins [(pc/connect-plugin {::pc/register all-resolvers})
                                    (preprocess-parser-plugin log-requests)
                                    p/error-handler-plugin
                                    p/request-cache-plugin
                                    (p/post-process-parser-plugin p/elide-not-found)
                                    p/trace-plugin]})
        trace?      (not (nil? (System/getProperty "trace")))]
    (fn wrapped-parser [env tx]
      (real-parser env
        (cond-> tx trace? (conj :com.wsscode.pathom/trace))))))

(defstate parser
  :start (build-parser))
