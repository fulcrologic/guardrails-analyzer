(ns com.fulcrologic.guardrails-pro.daemon.lsp.core
  (:require
    [com.fulcrologic.guardrails-pro.daemon.lsp.server :as lsp.server]
    [mount.core :refer [defstate]]))

;; TASK: pick an open port, write to .guardrails-pro-port

(defstate lsp-server
  :start (lsp.server/start-lsp 9999)
  :stop (lsp.server/stop-lsp lsp-server))
