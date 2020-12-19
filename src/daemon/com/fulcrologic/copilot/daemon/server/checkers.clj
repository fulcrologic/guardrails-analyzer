(ns com.fulcrologic.copilot.daemon.server.checkers
  (:require
    [com.fulcrologic.copilot.daemon.server.connection-management :as cp.conn]
    [com.fulcrologic.copilot.forms :as cp.forms]
    [com.fulcrologic.copilot.reader :as cp.reader]
    [com.fulcrologicpro.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defn notify-checker! [ws checker-cid event checker-info->data]
  (log/debug "notifiying checkers of event:" event)
  (when-let [checker-info (get @cp.conn/registered-checkers checker-cid)]
    (log/debug "notifying checker:" checker-cid checker-info)
    (wsp/push ws checker-cid event
      (checker-info->data checker-info))))

(defn opts->check-type [{:keys [refresh?]}]
  (if refresh? :refresh-and-check! :check!))

(defn check-file! [ws checker-cid path opts]
  (notify-checker! ws checker-cid (opts->check-type opts)
    (fn [{:keys [checker-type]}]
      (-> (cp.reader/read-file path checker-type)
        (update :forms cp.forms/form-expression)))))

(defn root-form-at? [cursor-line ?form]
  (let [{:keys [line end-line]} (meta ?form)]
    (<= line cursor-line end-line)))

(defn check-root-form! [ws checker-cid path line opts]
  (notify-checker! ws checker-cid (opts->check-type opts)
    (fn [{:keys [checker-type]}]
      (-> (cp.reader/read-file path checker-type)
        (update :forms
          #(->> %
             (filter (partial root-form-at? line))
             (cp.forms/form-expression)))))))
