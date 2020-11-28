(ns com.fulcrologic.copilot.daemon.lsp.core
  (:require
    [com.fulcrologic.copilot.daemon.lsp.server :as lsp.server]
    [mount.core :refer [defstate]]))

(defstate lsp-server
  :start (lsp.server/start-lsp)
  :stop (lsp.server/stop-lsp lsp-server))
