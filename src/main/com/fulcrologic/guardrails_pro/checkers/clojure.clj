(ns com.fulcrologic.guardrails-pro.checkers.clojure
  (:require
    [clojure.tools.namespace.repl :refer [refresh]]
    [com.fulcrologic.guardrails-pro.checker :as grp.checker]
    [com.fulcrologic.guardrails-pro.checkers.sente-client :as ws]
    [taoensso.timbre :as log])
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

(defn refresh-and-check! [env msg]
  (grp.checker/prepare-check! msg (partial report-analysis! env))
  (refresh :after 'com.fulcrologic.guardrails-pro.checker/run-prepared-check!))

(defn ?find-port []
  (try (Integer/parseInt (slurp ".guardrails-pro/daemon.port"))
    (catch FileNotFoundException _ nil)))

(defn start! [{:keys [host port]
               :or   {host "localhost"}}]
  (prn ::start! host port)
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
                           :check! (refresh-and-check! env (:msg msg))
                           nil)
                         nil)))})
    @(promise)))
