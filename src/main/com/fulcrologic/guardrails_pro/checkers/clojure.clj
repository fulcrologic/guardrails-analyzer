(ns com.fulcrologic.guardrails-pro.checkers.clojure
  (:require
    [com.fulcrologic.guardrails-pro.checker :as grp.checker]
    [com.fulcrologic.guardrails-pro.checkers.sente-client :as ws])
  (:import
    (java.util UUID)))

(defonce msg-cbs (atom {}))

(defn send-msg! [{:keys [client]} x & [cb]]
  (let [msg-id (subs (str (UUID/randomUUID)) 0 6)]
    (when cb (swap! msg-cbs assoc msg-id cb))
    (ws/send! client [x msg-id])))

(defn send-mutation! [env sym params]
  (send-msg! env
    [:fulcro.client/API [(list sym params)]]
    (fn [& args]
      (prn :on-mut sym :resp/args args))))

(defn report-analysis! [env]
  (let [analysis (grp.checker/gather-analysis!)]
    (send-mutation! env 'daemon/report-analysis analysis)))

(defn refresh-and-check! [{:as env :keys [refresh]} msg]
  (grp.checker/prepare-check! msg (partial report-analysis! env))
  (refresh :after 'com.fulcrologic.guardrails-pro.checker/run-prepared-check!))

(defmulti on-ws-msg!
  (fn [env edn]
    (cond
      (keyword? edn) edn
      (vector? edn)
      (cond
        (keyword? (first edn)) (first edn)
        (map? (first edn)) ::response
        (vector? (first edn)) ::messages
        :else :default))))

(defmethod on-ws-msg! :chsk/ws-ping [_ _] nil)
(defmethod on-ws-msg! :chsk/handshake [_ _] (prn :shook-hands))

(defmethod on-ws-msg! ::response [_ [response msg-id]]
  (when-let [cb (get msg-cbs msg-id)]
    (cb response)))

(defmethod on-ws-msg! ::messages [env messages]
  (doseq [[dispatch msg] messages]
    (prn :disp dispatch)
    (case dispatch
      :api/server-push
      (case (:topic msg)
        :check! (refresh-and-check! env (:msg msg)))
      nil)))

(defmethod on-ws-msg! :default [_ edn]
  (prn :on-ws-msg/default edn))

(defn start! [{:keys [host port]
               :or   {host "localhost"
                      port 3001}}
              refresh]
  (prn ::start! host port)
  (let [root-ns *ns*
        make-env (fn [client]
                   {:client client :refresh refresh})]
    (ws/connect! (str "ws://" host ":" port "/chsk?client-id=" (UUID/randomUUID))
      {:on-connect (fn [client]
                     (send-mutation! (make-env client) 'daemon/register-checker
                       {:checker-type :clj}))
       :on-receive (fn [client edn]
                     (try
                       (binding [*ns* root-ns]
                         (on-ws-msg! (make-env client) edn))
                       (catch Throwable e
                         (prn :ERROR! e)
                         (throw e))))})
    @(promise)))
