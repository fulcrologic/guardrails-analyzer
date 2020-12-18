(ns com.fulcrologic.copilot.daemon.lsp.commands
  (:require
    [com.fulcrologic.copilot.daemon.server.checkers :as daemon.check]
    [com.fulcrologic.copilot.daemon.server.connection-management :as cp.conn]
    [com.fulcrologic.copilot.daemon.server.websockets :refer [websockets]]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defn check-file! [path opts]
  (log/debug "lsp.commands/check-file!" path opts)
  (when-let [checker-cid (cp.conn/get-checker-for path)]
    (daemon.check/check-file! websockets checker-cid path opts)))

(defn check-root-form! [path line opts]
  (log/debug "lsp.commands/check-root-form!" path line opts)
  (when-let [checker-cid (cp.conn/get-checker-for path)]
    (daemon.check/check-root-form! websockets checker-cid path line opts)))

(def commands
  {"check-file!"      check-file!
   "check-root-form!" check-root-form!})
