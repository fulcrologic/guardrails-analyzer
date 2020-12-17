(ns com.fulcrologic.copilot.daemon.logging
  (:require
    [com.fulcrologic.copilot.logging :as cp.log]
    [mount.core :refer [defstate]]))

(defn configure-logging! []
  (let [log-dir ".copilot/logs"]
    (cp.log/clear-old-logs! log-dir)
    (cp.log/add-appender! log-dir "daemon.%s.log")))

(defstate logging
  :start (configure-logging!))
