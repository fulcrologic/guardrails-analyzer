(ns com.fulcrologic.copilot.daemon.main
  (:require
    [com.fulcrologic.copilot.daemon.logging]
    [com.fulcrologic.copilot.daemon.server.http-server]
    [com.fulcrologic.copilot.daemon.lsp.core]
    [mount.core :as mount])
  (:gen-class))

(defn -main [args]
  (assert (= (System/getProperty "user.dir")
            (System/getProperty "user.home"))
    (str "Copilot Daemon must be run in user's home directory: "
      (System/getProperty "user.home")))
  (mount/start))
