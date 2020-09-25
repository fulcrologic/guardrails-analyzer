(ns com.fulcrologic.guardrails-pro.daemon.lsp.core
  (:require
    [com.fulcrologic.guardrails-pro.daemon.lsp.server :as lsp.server]
    [mount.core :refer [defstate]]))

(defstate lsp-server
  :start (lsp.server/start-lsp)
  :stop (lsp.server/stop-lsp lsp-server))
