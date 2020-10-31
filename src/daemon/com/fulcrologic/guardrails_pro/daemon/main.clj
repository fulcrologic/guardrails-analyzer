(ns com.fulcrologic.guardrails-pro.daemon.main
  (:require
    [com.fulcrologic.guardrails-pro.daemon.server.http-server]
    [com.fulcrologic.guardrails-pro.daemon.lsp.core]
    [mount.core :as mount])
  (:gen-class))

(defn -main []
  (mount/start))
