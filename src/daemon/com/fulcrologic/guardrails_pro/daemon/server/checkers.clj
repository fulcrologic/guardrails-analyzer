(ns com.fulcrologic.copilot.daemon.server.checkers
  (:require
    [com.fulcrologicpro.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologic.copilot.daemon.reader :as reader]
    [com.fulcrologic.copilot.daemon.server.connection-management :refer [registered-checkers]]
    [com.fulcrologic.copilot.forms :as grp.forms]
    [com.fulcrologic.copilot.logging :as log]))

(defn notify-checkers! [ws event checker-info->data]
  (log/info "notifiying checkers of event:" event)
  (doseq [[cid checker-info] @registered-checkers]
    (log/info "notifying checker with id:" cid)
    (log/debug "notifying checker with info:" checker-info)
    (wsp/push ws cid event
      (checker-info->data checker-info))))

(defn opts->check-type [{:keys [refresh?]}]
  (if refresh? :refresh-and-check! :check!))

(defn check-file! [ws path opts]
  (notify-checkers! ws (opts->check-type opts)
    (fn [{:keys [checker-type]}]
      (let [{:keys [NS forms]} (reader/read-file path checker-type)]
        {:forms (grp.forms/form-expression forms)
         :file  path
         :NS    NS}))))

(defn root-form-at? [cursor-line ?form]
  (let [{:keys [line end-line]} (meta ?form)]
    (<= line cursor-line end-line)))

(defn check-root-form! [ws path line opts]
  (notify-checkers! ws (opts->check-type opts)
    (fn [{:keys [checker-type]}]
      (let [{:keys [NS forms]} (reader/read-file path checker-type)]
        {:forms (->> forms
                  (filter (partial root-form-at? line))
                  (grp.forms/form-expression))
         :file path
         :NS   NS}))))
