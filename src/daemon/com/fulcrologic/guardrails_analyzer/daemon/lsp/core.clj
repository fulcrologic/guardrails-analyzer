(ns com.fulcrologic.guardrails-analyzer.daemon.lsp.core
  (:require
    [com.fulcrologic.guardrails-analyzer.daemon.lsp.server :as lsp.server]
    [mount.core :refer [defstate]]))

(defstate lsp-server
  :start (lsp.server/start-lsp)
  :stop (lsp.server/stop-lsp lsp-server))
