(ns com.fulcrologic.guardrails-analyzer.daemon.lsp.commands
  (:require
   [com.fulcrologic.guardrails-analyzer.daemon.lsp.diagnostics :as lsp.diag]
   [com.fulcrologic.guardrails-analyzer.daemon.server.checkers :as daemon.check]
   [com.fulcrologic.guardrails-analyzer.daemon.server.connection-management :as cp.conn]
   [com.fulcrologic.guardrails-analyzer.daemon.server.websockets :refer [websockets]]
   [com.fulcrologicpro.taoensso.timbre :as log]))

(defn- safe-checker-for
  "Returns the checker-cid that owns `path` only if `path` canonicalizes to a
   location inside that checker's `:project-dir`. This guards against
   path-traversal attacks where the naive prefix match in
   `cp.conn/get-checker-for` would otherwise route an editor-supplied path
   like `/proj/../etc/passwd` to a `/proj` checker. Returns `nil` if no
   matching checker exists or the path escapes the project."
  [path]
  (when-let [checker-cid (cp.conn/get-checker-for path)]
    (when-let [checker-info (get @cp.conn/registered-checkers checker-cid)]
      (when (daemon.check/safe-path-under? path (:project-dir checker-info))
        checker-cid))))

(defn check-file! [client-id path opts]
  (log/debug "lsp.commands/check-file!" path opts)
  (if-let [checker-cid (safe-checker-for path)]
    (daemon.check/check-file! websockets checker-cid path opts)
    (lsp.diag/report-no-checker! client-id path)))

(defn check-root-form! [client-id path line opts]
  (log/debug "lsp.commands/check-root-form!" path line opts)
  (if-let [checker-cid (safe-checker-for path)]
    (daemon.check/check-root-form! websockets checker-cid path line opts)
    (lsp.diag/report-no-checker! client-id path)))

(def commands
  {"check-file!"      check-file!
   "check-root-form!" check-root-form!})
