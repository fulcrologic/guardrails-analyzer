(ns com.fulcrologic.copilot.daemon.server.pathom
  (:require
    [com.fulcrologic.copilot.daemon.lsp.diagnostics :as lsp.diag]
    [com.fulcrologic.copilot.daemon.server.bindings :as bindings]
    [com.fulcrologic.copilot.daemon.server.checkers :as daemon.check]
    [com.fulcrologic.copilot.daemon.server.connection-management :as cp.conn]
    [com.fulcrologic.copilot.daemon.server.problems :as problems]
    [com.fulcrologic.copilot.dot-config :as cp.cfg]
    [com.fulcrologicpro.taoensso.timbre :as log]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [mount.core :refer [defstate]]
    [org.httpkit.client :as http]))

;; CONTEXT: web ui (viewer) queries for all problems
(pc/defresolver all-problems
  [{viewer-cid :cid} _params]
  {::pc/output [:all-problems]}
  {:all-problems (problems/get! (cp.conn/viewer->checker viewer-cid))})

;; CONTEXT: checker reports error
(pc/defmutation report-error
  [{checker-cid :cid :keys [websockets]} {:keys [error]}]
  {::pc/sym 'daemon/report-error}
  (cp.conn/report-error! websockets checker-cid error)
  (let [checker-info (get @cp.conn/registered-checkers checker-cid)]
    (lsp.diag/report-error! checker-info error))
  {})

;; CONTEXT: checker reports analysis
(pc/defmutation report-analysis
  [{checker-cid :cid :keys [websockets]} {:keys [bindings problems]}]
  {::pc/sym 'daemon/report-analysis}
  (problems/set! checker-cid problems)
  (bindings/set! checker-cid bindings)
  (cp.conn/update-viewers-for! websockets checker-cid)
  (let [checker-info (get @cp.conn/registered-checkers checker-cid)]
    (lsp.diag/update-problems! checker-info problems))
  {})

;; CONTEXT: viewers subscribe to problems & bindings (eg: IJ, lsp clients, web ui)
(pc/defmutation subscribe
  [{viewer-cid :cid :keys [websockets]} viewer-info]
  {::pc/sym 'daemon/subscribe}
  (log/debug "Client subscribed to error updates: " viewer-cid)
  (swap! cp.conn/subscribed-viewers assoc viewer-cid viewer-info)
  (cp.conn/update-viewer! websockets viewer-cid viewer-info)
  {})

;; CONTEXT: checkers register with their capabilities / project directory
(pc/defmutation register-checker
  [{checker-cid :cid} checker-info]
  {::pc/sym 'daemon/register-checker}
  (log/debug "Checker registered: " checker-cid)
  (swap! cp.conn/registered-checkers assoc checker-cid checker-info)
  {})

(pc/defmutation check-current-file
  [{viewer-cid :cid :keys [websockets]} {:keys [file opts]}]
  {::pc/sym 'daemon/check-current-file}
  (if-let [checker-cid (cp.conn/viewer->checker viewer-cid)]
    (daemon.check/check-file! websockets checker-cid file opts)
    (cp.conn/report-no-checker! websockets viewer-cid file)))

(pc/defmutation check-root-form
  [{viewer-cid :cid :keys [websockets]} {:keys [file line opts]}]
  {::pc/sym 'daemon/check-root-form}
  (if-let [checker-cid (cp.conn/viewer->checker viewer-cid)]
    (daemon.check/check-root-form! websockets checker-cid file line opts)
    (cp.conn/report-no-checker! websockets viewer-cid file)))

(pc/defmutation report-analytics
  [{:keys [dot-config]} analytics]
  {::pc/sym 'daemon/report-analytics}
  (try
    (let [{:keys [status] :as resp}
          (if (System/getProperty "dev")
            (do (log/debug "analytics:" analytics)
                {:status 200})
            (if (get dot-config :analytics? false)
              @(http/post "https://fulcrologic.com/analytics"
                 {:multipart [{:name "password" :content "!!!uploadenzie"}
                              {:name "number" :content (str (:license/number analytics))}
                              {:name "file" :content (pr-str analytics) :filename "analytics"}]})
              {:status 200}))]
      (if (= status 200)
        {:status :ok}
        (do (log/error "Failed to send analytics to server because:" resp)
            {:status :failed})))
    (catch Exception e
      (log/error e "Failed to send analytics!")
      {:status :error})))

(def all-resolvers [all-problems
                    report-analysis report-error
                    subscribe register-checker
                    check-current-file check-root-form
                    report-analytics])

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
                                    :dot-config              (cp.cfg/load-config!)}
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
