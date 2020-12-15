(ns com.fulcrologic.copilot.daemon.logging
  (:require
    [com.fulcrologic.copilot.logging :as cp.log]
    [mount.core :refer [defstate]]))

(defn configure-logging! []
  (let [log-file ".copilot/logs/daemon.%s.log"]
    (cp.log/add-appender! log-file)))

(defstate logging
  :start (configure-logging!))
