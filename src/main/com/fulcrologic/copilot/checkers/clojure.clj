(ns com.fulcrologic.copilot.checkers.clojure
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.namespace.repl :as tools-ns :refer [refresh set-refresh-dirs]]
    [com.fulcrologic.copilot.checker :as cp.checker]
    [com.fulcrologic.copilot.checkers.sente-client :as ws]
    [com.fulcrologic.guardrails.config :as gr.cfg]
    [com.fulcrologicpro.taoensso.timbre :as log]
    [com.fulcrologic.copilot.logging :as cp.log])
  (:import
    (java.io FileNotFoundException)))

(defn send-mutation! [env sym params]
  (log/debug "Sending mutation:" sym)
  (ws/send! env
    [:fulcro.client/API [(list sym params)]]
    (fn [& args]
      (log/info :on-mut sym :resp/args args))))

(defn report-analysis! [env]
  (let [analysis (cp.checker/gather-analysis!)]
    (send-mutation! env 'daemon/report-analysis analysis)))

(defn report-error! [env error]
  (send-mutation! env 'daemon/report-error
    {:error (str/join "\n"
              [error (when-let [cause (.getCause error)]
                       (str "Cause: " cause))
               "See checker logs for more detailed information."])}))

(defn check! [env {:as msg :keys [NS]}]
  (when (try (require (symbol NS) :reload) true
          (catch Exception e
            (log/error e "Failed to reload:")
            (report-error! env e)
            false))
    (cp.checker/check! msg (partial report-analysis! env))) )

(defn refresh-and-check! [env msg]
  (cp.checker/prepare-check! msg (partial report-analysis! env))
  (let [?err (refresh :after 'com.fulcrologic.copilot.checker/run-prepared-check!)]
    (when (instance? Throwable ?err)
      (log/error ?err "Failed to reload:")
      (report-error! env ?err))))

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
    :port - An integer. The daemon port. Usually found automatically using the .copilot generated folder.
    :src-dirs - A vector of strings. The directories that contain source. If not supplied this assumes you will manually set-refresh-dirs from
              tools ns repl before starting the checker.
    :main-ns - A symbol. The main ns of the software being checked. This ensures the tree of deps are required into the env at startup.

    Sets an atom in this ns with the resulting websocket handler, so it can be shutdown for safe ns refresh.
    "
  ([] (start @ws/default-client-options))
  ([{:keys [host port src-dirs main-ns]
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
     (ws/run!
       {:host       host
        :?port-fn   (if port (constantly port) ?find-port)
        :on-connect (fn [env]
                      (try
                        (send-mutation! env 'daemon/register-checker
                          {:checker-type :clj
                           :project-dir  (System/getProperty "user.dir")})
                        (catch Exception e
                          (log/error e "Failed to register with daemon!"))))
        :on-message (fn [env [dispatch msg]]
                      (try
                        (binding [*ns* root-ns]
                          (case dispatch
                            :api/server-push
                            (case (:topic msg)
                              :check! (check! env (:msg msg))
                              :refresh-and-check! (refresh-and-check! env (:msg msg))
                              nil)
                            nil))
                        (catch Exception e
                          (log/error e "Unexpected exception!")
                          (report-error! env e))))}))))

(defn start!
  "Tools deps entry point. DOES NOT RETURN, but will exit if copilotd isn't started yet.

   Start the checker and block forever (websocket processing happens on daemon thread). Use `start` if
   you want to regain the thread; however, if you exit that thread the JVM can exit.

  :host - The IP where the checker daemon is running. Defaults to localhost.
  :port - An integer. The daemon port. Usually found automatically using the .copilot generated folder.
  :src-dirs - A vector of strings. The directories that contain source. If not supplied this assumes you will manually set-refresh-dirs from
            tools ns repl before starting the checker.
  :main-ns - A symbol. The main ns of the software being checked. This ensures the tree of deps are required into the env at startup.
  "
  [options]
  (when (start options)
    @(promise)))

(defn stop!
  "Stop the checker. Kills any active websocket and resets the atom that tracks the network connection."
  []
  (ws/shutdown!))

(defn reload!
  "Stops the checker, reloads the checker code, and starts the checker. Used for internal development. Requires
   namespace reload be set to reload the checker's code."
  []
  (stop!)
  (refresh :after `start))

(comment
  (reload!)
  (stop!)
  (System/getProperty "guardrails.mode")
  (reset! ws/default-client-options {:host     "localhost"
                                     :port     3050
                                     :src-dirs ["src/main" "src/test"
                                                "/Users/tonykay/fulcrologic/copilot/src/main"]
                                     :main-ns
                                     ;`com.fulcrologic.fulcro.application
                                               `com.fulcrologic.copilot.checkers.clojure
                                     #_'dataico.server-components.middleware})
  (start))
