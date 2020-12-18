(ns com.fulcrologic.copilot.daemon.logging
  (:require
    [com.fulcrologic.copilot.logging :as cp.log]
    [mount.core :refer [defstate]]))

(defstate logging
  :start (cp.log/configure-logging! "daemon.%s.log"))
