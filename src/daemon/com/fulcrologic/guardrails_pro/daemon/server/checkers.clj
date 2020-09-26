(ns com.fulcrologic.guardrails-pro.daemon.server.checkers
  (:require
    [com.fulcrologic.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologic.guardrails-pro.daemon.server.connection-management :refer [registered-checkers]]
    [com.fulcrologic.guardrails-pro.daemon.server.websockets :refer [websockets]]
    [taoensso.timbre :as log]))

(defn notify-checkers! [event data]
  (log/info "notifiying checkers of event:" event)
  (doseq [cid @registered-checkers]
    (log/info "notifying checker with id:" cid)
    (wsp/push websockets cid event data)))
