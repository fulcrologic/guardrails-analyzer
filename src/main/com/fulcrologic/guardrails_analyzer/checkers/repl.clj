(ns ^:clj-reload/no-reload com.fulcrologic.guardrails-analyzer.checkers.repl
  "In-process checker for REPL-driven development.

   Connects to the guardrails daemon via WebSocket, auto-launching
   the daemon as a subprocess if one is not already running.

   Two usage modes:

   **Dev REPL** (default) — call `(start)` with no options. The analyzer
   checks against whatever is currently loaded. You manage your own code
   loading and reloading. Editor `:refresh-and-check!` commands just check
   without reloading.

   **Dedicated REPL** — call `(start {:src-dirs [\"src/main\"]})`. The
   analyzer will auto-reload changed namespaces when the editor triggers
   a refresh-and-check. Use this in a REPL you do not work in.

   Usage:
     (start)                                    ;; dev REPL mode
     (start {:src-dirs [\"src/main\" \"src/dev\"]}) ;; dedicated REPL mode
     (check-ns 'my.app.core)
     (stop)"
  (:require
   [clj-reload.core :as reload]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.guardrails-analyzer.analytics :as cp.analytics]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.checker :as cp.checker]
   [com.fulcrologic.guardrails-analyzer.forms :as cp.forms]
   [com.fulcrologic.guardrails-analyzer.reader :as cp.reader]
   [com.fulcrologic.guardrails.config :as gr.cfg]
   [com.fulcrologicpro.fulcro.application :as app]
   [com.fulcrologicpro.fulcro.components :as comp]
   [com.fulcrologicpro.fulcro.mutations :as m :refer [defmutation]]
   [com.fulcrologicpro.fulcro.networking.websockets-client :as fws]
   [com.fulcrologic.guardrails-analyzer.log :as log])
  (:import
   (java.io File FileNotFoundException)
   (java.net Socket)))

(defonce APP (app/headless-synchronous-app {}))
(defonce ^:private reload-enabled? (atom false))

;; ===== Remote mutations (sent to daemon via WebSocket) =====

(defmutation report-analysis [_]
  (remote [env]
          (m/with-server-side-mutation env 'daemon/report-analysis)))

(defmutation report-error [_]
  (remote [env]
          (m/with-server-side-mutation env 'daemon/report-error)))

(defmutation register-checker [_]
  (remote [env]
          (m/with-server-side-mutation env 'daemon/register-checker)))

(defmutation report-analytics [_]
  (ok-action [{{:keys [status-code body]} :result :as env}]
             (when (and (= 200 status-code)
                        (= :ok (get-in body ['daemon/report-analytics :status])))
               (cp.analytics/clear-analytics!)))
  (remote [env]
          (m/with-server-side-mutation env 'daemon/report-analytics)))

;; ===== Reporting helpers =====

(defn- report-analysis! []
  (let [analysis (cp.checker/gather-analysis!)]
    (comp/transact! APP [(report-analysis analysis)])))

(defn- report-error! [error]
  (let [params {:error (str/join "\n"
                                 [error
                                  (when-let [cause (.getCause error)]
                                    (str "Cause: " cause))
                                  "See checker logs for more detailed information."])}]
    (comp/transact! APP [(report-error params)])))

(defn- on-check-done! []
  (comp/transact! APP [(report-analytics (cp.analytics/gather-analytics!))])
  (report-analysis!))

;; ===== Check operations =====

(defn- do-check!
  "Runs analysis on `msg` against the currently-loaded state of the REPL.
   Does NOT reload namespaces."
  [{:as msg :keys [NS]}]
  (try
    (cp.checker/check! msg on-check-done!)
    (catch Exception e
      (log/error e "Failed to check namespace" NS)
      (report-error! e))))

(defn- refresh-and-check!
  "Reloads changed namespaces via clj-reload (if enabled), then checks.
   When `reload-enabled?` is false (dev REPL mode), just checks without reloading."
  [msg]
  (let [reload-ok? (if @reload-enabled?
                     (try (reload/reload) true
                          (catch Throwable err
                            (log/error err "Failed to reload:")
                            (report-error! err)
                            false))
                     true)]
    (when reload-ok?
      (do-check! msg))))

;; ===== Daemon discovery and auto-launch =====

(defn- port-file
  "Returns the daemon port file at `~/.guardrails/daemon.port`."
  ^File []
  (io/file (System/getProperty "user.home") ".guardrails" "daemon.port"))

(defn- port-reachable?
  "Returns true if a TCP connection can be established to localhost on `port`."
  [port]
  (try
    (with-open [_ (Socket. "localhost" (int port))]
      true)
    (catch Exception _ false)))

(defn- ?read-port
  "Reads the daemon port from the port file and verifies the daemon is
   actually listening. Returns the port number or nil if the port file
   is missing or the daemon is not responding."
  []
  (when-let [port (try (some-> (port-file) slurp str/trim Integer/parseInt)
                       (catch FileNotFoundException _ nil))]
    (if (port-reachable? port)
      port
      (do
        (log/info "Stale port file found (port" port "not reachable). Removing.")
        (.delete (port-file))
        nil))))

(defn- find-clojure-cmd
  "Finds the `clojure` command on PATH. Returns the path string or nil."
  []
  (let [candidates (if (= "Windows" (System/getProperty "os.name"))
                     ["clj.cmd" "clojure.cmd"]
                     ["clojure"])]
    (some (fn [cmd]
            (try
              (let [proc (.start (doto (ProcessBuilder. ["which" cmd])
                                   (.redirectErrorStream true)))
                    path (str/trim (slurp (.getInputStream proc)))]
                (when (zero? (.waitFor proc))
                  path))
              (catch Exception _ nil)))
          candidates)))

(defn- latest-daemon-version
  "Queries Clojars for the latest version of guardrails-analyzer-daemon.
   Returns the version string or nil on failure."
  []
  (try
    (let [url  "https://clojars.org/api/artifacts/com.fulcrologic/guardrails-analyzer-daemon"
          conn (doto (.openConnection (java.net.URL. url))
                 (.setRequestProperty "Accept" "application/json")
                 (.setConnectTimeout 5000)
                 (.setReadTimeout 5000))
          body (slurp (.getInputStream conn))]
      (second (re-find #"\"latest_release\"\s*:\s*\"([^\"]+)\"" body)))
    (catch Exception e
      (log/warn "Could not query Clojars for daemon version:" (.getMessage e))
      nil)))

(defn- launch-daemon!
  "Launches the daemon as a subprocess. Returns true if the daemon starts
   successfully (port file appears within timeout), false otherwise.
   Uses -Srepro -Sdeps to pull the daemon artifact from Clojars,
   independent of the user's project deps.edn."
  []
  (if-let [clj-cmd (find-clojure-cmd)]
    (let [log-file (io/file (System/getProperty "user.home") ".guardrails" "daemon.log")
          _        (io/make-parents log-file)
          version  (latest-daemon-version)
          cmd      (if version
                     [clj-cmd "-Srepro"
                      "-Sdeps" (str "{:deps {com.fulcrologic/guardrails-analyzer-daemon {:mvn/version \"" version "\"}}}")
                      "-M" "-m" "com.fulcrologic.guardrails-analyzer.daemon.main"]
                     [clj-cmd "-M" "-m" "com.fulcrologic.guardrails-analyzer.daemon.main"])
          builder  (doto (ProcessBuilder. ^java.util.List cmd)
                     (.directory (io/file (System/getProperty "user.dir")))
                     (.redirectOutput log-file)
                     (.redirectErrorStream true))]
      (log/info "Starting guardrails daemon:" (str/join " " cmd))
      (try
        (let [proc (.start builder)]
          (loop [attempts 0]
            (cond
              (>= attempts 30)
              (do (log/error "Daemon did not start within 15 seconds. Check" (str log-file))
                  false)

              (some? (?read-port))
              (do (log/info "Guardrails daemon started (pid" (.pid proc) ")")
                  true)

              :else
              (do (Thread/sleep 500)
                  (recur (inc attempts))))))
        (catch Exception e
          (log/error e "Failed to launch daemon subprocess")
          false)))
    (do (log/warn "Could not find `clojure` on PATH")
        false)))

(defn- ensure-daemon!
  "Returns the daemon port, auto-launching if needed.
   Returns the port number or nil if the daemon could not be started."
  []
  (or (?read-port)
      (do
        (log/info "No guardrails daemon found. Attempting to start one...")
        (if (launch-daemon!)
          (?read-port)
          (do
            (log/error
             (str "Could not auto-start the daemon. Start it manually:\n"
                  "  clojure -Sdeps '{:deps {com.fulcrologic/guardrails-analyzer-daemon {:mvn/version \"VERSION\"}}}' \\\n"
                  "          -M -m com.fulcrologic.guardrails-analyzer.daemon.main\n"
                  "  (Find VERSION at https://clojars.org/com.fulcrologic/guardrails-analyzer-daemon)"))
            nil)))))

;; ===== Public API =====

(defn start
  "Starts the in-process REPL checker and connects to the daemon.

   Auto-launches the daemon as a subprocess if one is not already running.
   Validates that guardrails is in `:pro` or `:all` mode before starting.

   `opts` is a map containing:

     * `:src-dirs` - (optional) A vector of source directory strings. When
       provided, enables auto-reload via clj-reload on editor refresh commands.
       Use this only in a dedicated analyzer REPL, not your working dev REPL.
     * `:main-ns` - (optional) A symbol. The main namespace to require at
       startup, ensuring its dependency tree is loaded."
  ([] (start {}))
  ([{:keys [src-dirs main-ns]}]
   (when-not (#{:pro :all} (:mode (gr.cfg/get-env-config)))
     (throw
      (new AssertionError
           (str "JVM property `guardrails.mode` must be set to `:pro` or `:all`."
                "\nFor clj: add `-J-Dguardrails.mode=:pro`"
                "\nFor deps.edn: add `:jvm-opts [\"-Dguardrails.mode=:pro\"]`"))))
   (log/info "Starting REPL checker with opts:" {:src-dirs src-dirs :main-ns main-ns})
   (when-let [ns-sym (some-> main-ns symbol)]
     (require ns-sym))
   (if (seq src-dirs)
     (do (reload/init {:dirs src-dirs})
         (reset! reload-enabled? true)
         (log/info "Auto-reload enabled for" src-dirs))
     (reset! reload-enabled? false))
   (let [root-ns *ns*]
     (if-let [port (ensure-daemon!)]
       (do
         (log/info "Connecting to daemon on port" port)
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
       (log/error "No guardrails daemon available. Checker not started.")))))

(defn- read-ns-file
  "Finds and reads the source file for `ns-sym` on the classpath.
   Returns the parsed message map or nil."
  [ns-sym]
  (let [base (-> (str ns-sym)
                 (str/replace \. \/)
                 (str/replace \- \_))]
    (if-let [resource (some (fn [ext]
                              (io/resource (str base ext)))
                            [".cljc" ".clj"])]
      (-> (cp.reader/read-file (str resource) :clj)
          (update :forms cp.forms/form-expression))
      (do (log/error "Could not find source file for namespace" ns-sym)
          nil))))

(defn- read-src-file
  "Reads and parses a source file at `path`.
   Returns the parsed message map."
  [path]
  (-> (cp.reader/read-file path :clj)
      (update :forms cp.forms/form-expression)))

(defn- compact-problems
  "Returns a compact vector of problems from the current analysis state.
   Each problem is a map with `:file`, `:line`, `:column`, `:severity`, and `:message`."
  []
  (let [{:keys [problems]} (cp.checker/gather-analysis!)]
    (mapv (fn [p]
            {:file     (::cp.art/file p)
             :line     (::cp.art/line-start p)
             :column   (::cp.art/column-start p)
             :severity (some-> (::cp.art/problem-type p) namespace)
             :message  (::cp.art/message p)})
          (filterv (fn [p]
                     (and (::cp.art/message p)
                          (not= "info" (some-> (::cp.art/problem-type p) namespace))))
                   problems))))

(defn check-file
  "Checks a source file at `path` and returns a vector of problems.
   Each problem is a map with `:file`, `:line`, `:column`, `:severity`, and `:message`.
   Does not push results to the daemon — use `check-file!` for that."
  [path]
  (let [msg (read-src-file path)]
    (cp.checker/check! msg (fn []))
    (compact-problems)))

(defn check-ns
  "Checks the namespace `ns-sym` and returns a vector of problems.
   Each problem is a map with `:file`, `:line`, `:column`, `:severity`, and `:message`.
   Does not push results to the daemon — use `check-ns!` for that."
  [ns-sym]
  (when-let [msg (read-ns-file ns-sym)]
    (cp.checker/check! msg (fn []))
    (compact-problems)))

(defn check-file!
  "Checks a source file at `path` and pushes results to the daemon (and thus your editor)."
  [path]
  (do-check! (read-src-file path)))

(defn check-ns!
  "Checks the namespace `ns-sym` and pushes results to the daemon (and thus your editor)."
  [ns-sym]
  (when-let [msg (read-ns-file ns-sym)]
    (do-check! msg)))

(defn stop
  "Disconnects the REPL checker from the daemon."
  []
  (app/set-remote! APP :remote nil)
  (reset! reload-enabled? false)
  (log/info "REPL checker stopped."))
