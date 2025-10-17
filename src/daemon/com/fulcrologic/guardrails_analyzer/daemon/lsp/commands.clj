(ns com.fulcrologic.guardrails-analyzer.daemon.lsp.commands
  (:require
    [com.fulcrologic.guardrails-analyzer.daemon.lsp.diagnostics :as lsp.diag]
    [com.fulcrologic.guardrails-analyzer.daemon.server.checkers :as daemon.check]
    [com.fulcrologic.guardrails-analyzer.daemon.server.connection-management :as cp.conn]
    [com.fulcrologic.guardrails-analyzer.daemon.server.websockets :refer [websockets]]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defn check-file! [client-id path opts]
  (log/debug "lsp.commands/check-file!" path opts)
  (if-let [checker-cid (cp.conn/get-checker-for path)]
    (daemon.check/check-file! websockets checker-cid path opts)
    (lsp.diag/report-no-checker! client-id path)))

(defn check-root-form! [client-id path line opts]
  (log/debug "lsp.commands/check-root-form!" path line opts)
  (if-let [checker-cid (cp.conn/get-checker-for path)]
    (daemon.check/check-root-form! websockets checker-cid path line opts)
    (lsp.diag/report-no-checker! client-id path)))

(def commands
  {"check-file!"      check-file!
   "check-root-form!" check-root-form!})
