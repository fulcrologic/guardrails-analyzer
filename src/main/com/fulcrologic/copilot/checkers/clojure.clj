(ns com.fulcrologic.copilot.checkers.clojure
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.namespace.repl :as tools-ns :refer [refresh set-refresh-dirs]]
    [com.fulcrologic.copilot.analytics :as cp.analytics]
    [com.fulcrologic.copilot.checker :as cp.checker]
    [com.fulcrologic.copilot.logging :as cp.log]
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

(defn report-analysis! []
  (let [analysis (cp.checker/gather-analysis!)]
    (comp/transact! APP [(report-analysis analysis)])))

(defmutation report-error [_]
  (remote [env]
    (m/with-server-side-mutation env 'daemon/report-error)))

(defmutation register-checker [_]
  (remote [env]
    (m/with-server-side-mutation env 'daemon/register-checker)))

(defn report-error! [error]
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

(defn on-check-done! []
  (comp/transact! APP [(report-analytics (cp.analytics/gather-analytics!))])
  (report-analysis!))

(defn check! [{:as msg :keys [NS]}]
  (when (try (require (symbol NS) :reload) true
             (catch Exception e
               (log/error e "Failed to reload:")
               (report-error! e)
               false))
    (cp.checker/check! msg on-check-done!)))

(defn refresh-and-check! [msg]
  (cp.checker/prepare-check! msg on-check-done!)
  (let [?err (refresh :after 'com.fulcrologic.copilot.checker/run-prepared-check!)]
    (when (instance? Throwable ?err)
      (log/error ?err "Failed to reload:")
      (report-error! ?err))))

(defn ?find-port []
  (try (some->> ".copilot/daemon.port"
         (io/file (System/getProperty "user.home"))
         (slurp)
         (Integer/parseInt)
         (log/spy :debug "Found daemon running on port:"))
    (catch FileNotFoundException _ nil)))

(defn start
  "Start the checker.

    :host - The IP where the checker daemon is running. Defaults to localhost.
    :src-dirs - A vector of strings. The directories that contain source. If not supplied this assumes you will manually set-refresh-dirs from
              tools ns repl before starting the checker.
    :main-ns - A symbol. The main ns of the software being checked. This ensures the tree of deps are required into the env at startup.

    Sets an atom in this ns with the resulting websocket handler, so it can be shutdown for safe ns refresh.
    "
  ([] (start {}))
  ([{:keys [host src-dirs main-ns]
     :or   {host "localhost"}
     :as   opts}]
   (when-not (#{:pro :copilot :all} (:mode (gr.cfg/get-env-config)))
     (throw
       (new AssertionError
         (str "JVM property `guardrails.mode` should be set to `:copilot`!"
           "\nFor clj: add `-J-Dguardrails.mode=:copilot`"
           "\nFor deps.edn: add `:jvm-opts [\"-Dguardrails.mode=:copilot\"]"))))
   (cp.log/configure-logging! "checker.clojure.%s.log")
   (log/info "Starting checker with opts:" opts)
   (when-let [ns-sym (some-> main-ns symbol)]
     (require ns-sym))
   (when (seq src-dirs)
     (apply set-refresh-dirs src-dirs))
   (let [root-ns *ns*]
     (if-let [port (?find-port)]
       (do
         (log/info "Checker looking for Daemon on port " port)
         (app/set-remote! APP :remote
           (fws/fulcro-websocket-remote
             {:host          (str "localhost:" port)
              :sente-options {:csrf-token-fn (fn [] nil)}
              :push-handler  (fn [{:keys [topic msg]}]
                               (binding [*ns* root-ns]
                                 (case topic
                                   :check! (check! msg)
                                   :refresh-and-check! (refresh-and-check! msg)
                                   nil)))}))
         (comp/transact! APP [(register-checker {:checker-type :clj
                                                 :project-dir  (System/getProperty "user.dir")})])
         true)
       (log/error "No copilot daemon found. Have you started copilotd? Are you running copilotd in the same directory as the project root?")))))

(defn start!
  "Tools deps entry point. DOES NOT RETURN, but will exit if copilotd isn't started yet.

   Start the checker and block forever (websocket processing happens on daemon thread). Use `start` if
   you want to regain the thread; however, if you exit that thread the JVM can exit.

  :host - The IP where the checker daemon is running. Defaults to localhost.
  :src-dirs - A vector of strings. The directories that contain source. If not supplied this assumes you will manually set-refresh-dirs from
            tools ns repl before starting the checker.
  :main-ns - A symbol. The main ns of the software being checked. This ensures the tree of deps are required into the env at startup.
  "
  [options]
  (when (start options)
    @(promise)))

(comment
  (start)
  )
