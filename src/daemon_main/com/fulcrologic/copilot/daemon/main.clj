(ns com.fulcrologic.copilot.daemon.main
  (:require
    [com.fulcrologic.copilot.daemon.logging]
    [com.fulcrologic.copilot.daemon.server.http-server :refer [port-file]]
    [com.fulcrologic.copilot.daemon.lsp.core]
    [com.fulcrologicpro.taoensso.timbre :as log]
    [mount.core :as mount])
  (:gen-class))

(defn -main [& args]
  (when-not (= (System/getProperty "user.dir")
              (System/getProperty "user.home"))
    (log/error "Copilot Daemon must be run in user's home directory: "
      (System/getProperty "user.home"))
    (System/exit 1))
  (when (.exists port-file)
    (log/error "Found an already running daemon!")
    (System/exit 1))
  (mount/start))

(comment
  (-main))
