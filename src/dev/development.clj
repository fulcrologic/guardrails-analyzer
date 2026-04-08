(ns development
  (:require
   [clj-reload.core :as reload]
   [com.fulcrologic.guardrails-analyzer.daemon.core]
   [mount.core :as mount]))

(defn start [] (mount/start))

(defn stop [] (mount/stop))

(defn restart []
  (stop)
  (reload/reload)
  (start))
