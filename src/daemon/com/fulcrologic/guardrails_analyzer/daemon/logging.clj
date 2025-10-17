(ns com.fulcrologic.guardrails-analyzer.daemon.logging
  (:require
    [com.fulcrologic.guardrails-analyzer.logging :as cp.log]
    [mount.core :refer [defstate]]))

(defstate logging
  :start (cp.log/configure-logging! "daemon.%s.log"))
