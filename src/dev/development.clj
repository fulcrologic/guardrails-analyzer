(ns development
  (:require
    [clojure.tools.namespace.repl :as tools-ns]
    [com.fulcrologic.guardrails-pro.daemon.server.http-server]
    [mount.core :as mount]))

(defn start [] (mount/start))

(defn stop [] (mount/stop))

(defn restart [] (stop) (tools-ns/refresh :after 'development/start))
