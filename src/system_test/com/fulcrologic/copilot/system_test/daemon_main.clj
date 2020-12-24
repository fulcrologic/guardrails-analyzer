(ns com.fulcrologic.copilot.system-test.daemon-main
  (:require
    com.fulcrologic.copilot.daemon.core
    [mount.core :as mount]))

(defn start! [opts]
  (prn ::start! opts)
  (mount/start))
