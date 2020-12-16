(ns com.fulcrologic.copilot.daemon.main
  (:require
    [com.fulcrologic.copilot.daemon.server.http-server]
    [com.fulcrologic.copilot.daemon.lsp.core]
    [mount.core :as mount])
  (:gen-class))

(defn -main [args]
  (mount/start))