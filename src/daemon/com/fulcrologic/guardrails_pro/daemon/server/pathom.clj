(ns com.fulcrologic.guardrails-pro.daemon.server.pathom
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.guardrails-pro.daemon.server.config :refer [config]]
    [com.fulcrologic.guardrails-pro.daemon.server.problems :as problems]
    [com.fulcrologic.guardrails-pro.daemon.server.bindings :as bindings]
    [com.fulcrologic.guardrails-pro.daemon.server.connection-management :as cmgmt]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [mount.core :refer [defstate]]
    [taoensso.timbre :as log]
    [clojure.set :as set]
    [com.fulcrologic.fulcro.networking.websocket-protocols :as wsp]))

(pc/defresolver all-problems [_env _params]
  {::pc/output [:all-problems]}
  {:all-problems (problems/get!)})

(pc/defmutation report-analysis [{:keys [websockets]} {:keys [bindings problems]}]
  {::pc/sym    'daemon/report-analysis
   ::pc/output [:result]}
  (problems/set! problems)
  (bindings/set! bindings)
  (cmgmt/update-viewers! websockets)
  {})

(pc/defmutation subscribe [{:keys [websockets cid]} _params]
  {::pc/sym 'daemon/subscribe}
  (log/info "Client subscribed to error updates: " cid)
  (swap! cmgmt/subscribed-viewers conj cid)
  (cmgmt/update-viewers! websockets cid)
  {})

(pc/defmutation register-checker [{:keys [cid] :as _env} _params]
  {::pc/sym 'daemon/register-checker}
  (log/info "Checker registered: " cid)
  (swap! cmgmt/registered-checkers conj cid)
  {})

(def all-resolvers [all-problems report-analysis subscribe register-checker])

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

(defn build-parser []
  (let [real-parser (p/parser
                      {::p/mutate  pc/mutate
                       ::p/env     {::p/reader               [p/map-reader
                                                              pc/reader2
                                                              pc/open-ident-reader
                                                              p/env-placeholder-reader]
                                    ::p/placeholder-prefixes #{">"}}
                       ::p/plugins [(pc/connect-plugin {::pc/register all-resolvers})
                                    (p/env-wrap-plugin (fn [env]
                                                         (assoc env
                                                           :config config)))
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
