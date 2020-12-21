(ns com.fulcrologic.copilot.daemon.server.http-server
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.copilot.daemon.server.middleware :refer [middleware]]
    [com.fulcrologicpro.taoensso.timbre :as log]
    [mount.core :refer [defstate]]
    [org.httpkit.server :as http-kit])
  (:import
    (java.net ServerSocket)))

(defn write-port-to-file! [file port]
  (io/make-parents file)
  (spit file port))

(defonce port-file
  (io/file (System/getProperty "user.home")
    ".copilot/daemon.port"))

(defstate http-server
  :start (let [socket    (new ServerSocket 0)
               open-port (.getLocalPort socket)
               config    {:port open-port}]
           (.close socket)
           (log/info "Starting Copilot Daemon HTTP Server with config:" (pr-str config))
           (.deleteOnExit port-file)
           (write-port-to-file! port-file open-port)
           (http-kit/run-server middleware config))
  :stop (http-server))
