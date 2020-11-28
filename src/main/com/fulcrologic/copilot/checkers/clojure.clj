(ns com.fulcrologic.copilot.checkers.clojure
  (:require
    [clojure.tools.namespace.repl :refer [refresh set-refresh-dirs]]
    [com.fulcrologic.copilot.checker :as grp.checker]
    [com.fulcrologic.copilot.checkers.sente-client :as ws]
    [com.fulcrologic.guardrails.config :as gr.cfg]
    [com.fulcrologic.copilot.logging :as log])
  (:import
    (java.io FileNotFoundException)))

(defn send-mutation! [env sym params]
  (ws/send! env
    [:fulcro.client/API [(list sym params)]]
    (fn [& args]
      (log/info :on-mut sym :resp/args args))))

(defn report-analysis! [env]
  (let [analysis (grp.checker/gather-analysis!)]
    (send-mutation! env 'daemon/report-analysis analysis)))

(defn check! [env {:as msg :keys [NS]}]
  (require (symbol NS) :reload)
  (grp.checker/check! msg (partial report-analysis! env)))

(defn refresh-and-check! [env msg]
  (grp.checker/prepare-check! msg (partial report-analysis! env))
  (refresh :after 'com.fulcrologic.copilot.checker/run-prepared-check!))

(defn ?find-port []
  (try (Integer/parseInt (slurp ".copilot/daemon.port"))
       (catch FileNotFoundException _ nil)))

(defn start!
  "Start the checker. Does not return.

  :host - The IP where the checker daemon is running. Defaults to localhost.
  :port - An integer. The daemon port. Usually found automatically using the .guardrails generated folder.
  :src-dirs - A vector of strings. The directories that contain source. If not supplied this assumes you will manually set-refresh-dirs from
            tools ns repl before starting the checker.
  :main-ns - A symbol. The main ns of the software being checked. This ensures the tree of deps are required into the env at startup.
  "
  [{:keys [host port src-dirs main-ns]
    :or   {host "localhost"}
    :as   opts}]
  (when-not (#{:pro :all} (:mode (gr.cfg/get-env-config)))
    (throw
      (new AssertionError
        (str "JVM property `guardrails.mode` should be set to `:pro`!"
          "\nFor clj: add `-J-Dguardrails.mode=:pro`"
          "\nFor deps.edn: add `:jvm-opts [\"-Dguardrails.mode=:pro\"]"))))
  (prn ::start! opts)
  (when-let [ns-sym (some-> main-ns symbol)]
    (require ns-sym))
  (when (seq src-dirs)
    (apply set-refresh-dirs src-dirs))
  (let [root-ns *ns*]
    (ws/connect! host
      {:?port-fn   (if port (constantly port) ?find-port)
       :on-connect (fn [env]
                     (send-mutation! env 'daemon/register-checker
                       {:checker-type :clj}))
       :on-message (fn [env [dispatch msg]]
                     (binding [*ns* root-ns]
                       (case dispatch
                         :api/server-push
                         (case (:topic msg)
                           :check! (check! env (:msg msg))
                           :refresh-and-check! (refresh-and-check! env (:msg msg))
                           nil)
                         nil)))})
    @(promise)))
