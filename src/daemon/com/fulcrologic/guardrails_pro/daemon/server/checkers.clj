(ns com.fulcrologic.guardrails-pro.daemon.server.checkers
  (:require
    [com.fulcrologic.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologic.guardrails-pro.daemon.reader :as reader]
    [com.fulcrologic.guardrails-pro.daemon.server.connection-management :refer [registered-checkers]]
    [com.fulcrologic.guardrails-pro.forms :as grp.forms]
    [taoensso.timbre :as log]))

(defn notify-checkers! [ws event data]
  (log/info "notifiying checkers of event:" event)
  (doseq [[cid _checker-info] @registered-checkers]
    (log/info "notifying checker with id:" cid)
    (wsp/push ws cid event data)))

(defn check-file! [ws path]
  (let [{:keys [NS forms]} (reader/read-file path)]
    (notify-checkers! ws :check!
      {:forms (grp.forms/form-expression forms)
       :file  path
       :NS    NS})))

(defn root-form-at? [cursor-line ?form]
  (let [{:keys [line end-line]} (meta ?form)]
    (<= line cursor-line end-line)))

(defn check-root-form! [ws path line]
  (let [{:keys [NS forms]} (reader/read-file path)]
    (notify-checkers! ws :check!
      {:forms (->> forms
                (filter (partial root-form-at? line))
                (grp.forms/form-expression))
       :file path
       :NS   NS})))
