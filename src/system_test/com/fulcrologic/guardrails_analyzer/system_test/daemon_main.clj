(ns com.fulcrologic.guardrails-analyzer.system-test.daemon-main
  (:require
    [com.fulcrologic.guardrails-analyzer.daemon.core]
    [mount.core :as mount]))

(defn start! [opts]
  (prn ::start! opts)
  (mount/start))
