(ns ^:clj-reload/no-reload com.fulcrologic.guardrails-analyzer.checkers.repl
  "A simplified in-process checker for REPL-driven development.

   Unlike the standalone `clojure` checker (which is designed to run in a separate JVM),
   this checker runs inside the user's own JVM and connects to the daemon via WebSocket.

   Usage:
     (start {:src-dirs [\"src/main\" \"src/dev\"]})
     (check-ns 'my.app.core)
     (stop)"
  (:require
   [clj-reload.core :as reload]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.guardrails-analyzer.analytics :as cp.analytics]
   [com.fulcrologic.guardrails-analyzer.checker :as cp.checker]
   [com.fulcrologic.guardrails-analyzer.logging :as cp.log]
   [com.fulcrologic.guardrails.config :as gr.cfg]
   [com.fulcrologicpro.fulcro.application :as app]
   [com.fulcrologicpro.fulcro.components :as comp]
   [com.fulcrologicpro.fulcro.mutations :as m :refer [defmutation]]
   [com.fulcrologicpro.fulcro.networking.websockets-client :as fws]
   [com.fulcrologicpro.taoensso.timbre :as log])
  (:import
   (java.io FileNotFoundException)))

(defonce APP (app/headless-synchronous-app {}))

(defmutation report-analysis [_]
  (remote [env]
          (m/with-server-side-mutation env 'daemon/report-analysis)))

(defn- report-analysis! []
  (let [analysis (cp.checker/gather-analysis!)]
    (comp/transact! APP [(report-analysis analysis)])))

(defmutation report-error [_]
  (remote [env]
          (m/with-server-side-mutation env 'daemon/report-error)))

(defmutation register-checker [_]
  (remote [env]
          (m/with-server-side-mutation env 'daemon/register-checker)))

(defn- report-error! [error]
  (let [params {:error (str/join "\n"
                                 [error (when-let [cause (.getCause error)]
                                          (str "Cause: " cause))
                                  "See checker logs for more detailed information."])}]
    (comp/transact! APP [(report-error params)])))

(defmutation report-analytics [_]
  (ok-action [{{:keys [status-code body]} :result :as env}]
             (when (and (= 200 status-code)
                        (= :ok (get-in body ['daemon/report-analytics :status])))
               (cp.analytics/clear-analytics!)))
  (remote [env]
          (m/with-server-side-mutation env 'daemon/report-analytics)))

(defn- on-check-done! []
  (comp/transact! APP [(report-analytics (cp.analytics/gather-analytics!))])
  (report-analysis!))

(defn- do-check! [{:as msg :keys [NS]}]
  (when (try (require (symbol NS) :reload) true
             (catch Exception e
               (log/error e "Failed to reload:")
               (report-error! e)
               false))
    (cp.checker/check! msg on-check-done!)))

(defn- refresh-and-check! [msg]
  (cp.checker/prepare-check! msg on-check-done!)
  (try
    (reload/reload)
    (cp.checker/run-prepared-check!)
    (catch Throwable ?err
      (log/error ?err "Failed to reload:")
      (report-error! ?err))))

(defn- ?find-port []
  (try (some->> ".guardrails/daemon.port"
                (io/file (System/getProperty "user.home"))
                (slurp)
                (Integer/parseInt)
                (log/spy :debug "Found daemon running on port:"))
       (catch FileNotFoundException _ nil)))

(defn start
  "Starts the in-process REPL checker and connects to the daemon.

   `opts` is a map containing:

     * `:host` - (optional) The IP where the daemon is running. Defaults to \"localhost\".
     * `:src-dirs` - (optional) A vector of source directory strings for clj-reload. If not supplied,
       you must call `(clj-reload.core/init {:dirs [...]})` before starting.
     * `:main-ns` - (optional) A symbol. The main namespace to require at startup, ensuring its
       dependency tree is loaded."
  ([] (start {}))
  ([{:keys [host src-dirs main-ns]
     :or   {host "localhost"}
     :as   opts}]
   (when-not (#{:pro :all} (:mode (gr.cfg/get-env-config)))
     (throw
      (new AssertionError
           (str "JVM property `guardrails.mode` should be set to `:pro`!"
                "\nFor clj: add `-J-Dguardrails.mode=:pro`"
                "\nFor deps.edn: add `:jvm-opts [\"-Dguardrails.mode=:pro\"]"))))
   (cp.log/configure-logging! "checker.repl.%s.log")
   (log/info "Starting REPL checker with opts:" opts)
   (when-let [ns-sym (some-> main-ns symbol)]
     (require ns-sym))
   (when (seq src-dirs)
     (reload/init {:dirs src-dirs}))
   (let [root-ns *ns*]
     (if-let [port (?find-port)]
       (do
         (log/info "REPL checker connecting to daemon on port" port)
         (app/set-remote! APP :remote
                          (fws/fulcro-websocket-remote
                           {:host          (str "localhost:" port)
                            :sente-options {:csrf-token-fn (fn [] nil)}
                            :push-handler  (fn [{:keys [topic msg]}]
                                             (binding [*ns* root-ns]
                                               (case topic
                                                 :check! (do-check! msg)
                                                 :refresh-and-check! (refresh-and-check! msg)
                                                 nil)))}))
         (comp/transact! APP [(register-checker {:checker-type :clj
                                                 :project-dir  (System/getProperty "user.dir")})])
         true)
       (log/error "No guardrails analyzer daemon found. Have you started it?")))))

(defn check-ns
  "Manually checks a single namespace `ns-sym` by reloading it and running the analyzer."
  [ns-sym]
  (do-check! {:NS ns-sym}))

(defn stop
  "Disconnects the REPL checker from the daemon by removing the websocket remote."
  []
  (app/set-remote! APP :remote nil)
  (log/info "REPL checker stopped."))
