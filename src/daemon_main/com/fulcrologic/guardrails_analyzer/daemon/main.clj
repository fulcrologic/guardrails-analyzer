(ns com.fulcrologic.guardrails-analyzer.daemon.main
  (:require
   [com.fulcrologic.guardrails-analyzer.daemon.logging]
   [com.fulcrologic.guardrails-analyzer.daemon.lsp.core]
   [com.fulcrologic.guardrails-analyzer.daemon.server.http-server :refer [port-file]]
   [com.fulcrologicpro.taoensso.timbre :as log]
   [mount.core :as mount])
  (:gen-class))

(defn -main [& args]
  (when (.exists port-file)
    (log/error "Found an already running daemon!")
    (System/exit 1))
  (mount/start))

(comment
  (-main))
