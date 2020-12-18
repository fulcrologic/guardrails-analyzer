(ns com.fulcrologic.copilot.daemon.server.http-server
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.copilot.daemon.server.config :refer [config]]
    [com.fulcrologic.copilot.daemon.server.middleware :refer [middleware]]
    [mount.core :refer [defstate]]
    [org.httpkit.server :as http-kit]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defn write-port-to-file! [file port]
  (io/make-parents file)
  (spit file port))

(defonce port-file
  (io/file (System/getProperty "user.home")
    ".copilot/daemon.port"))

(defstate http-server
  :start
  (let [cfg (::http-kit/config config)]
    (log/info "Starting HTTP Server with config: " (pr-str cfg))
    (when (.exists port-file)
      (log/error "Found an already running daemon!")
      (System/exit 1))
    (.deleteOnExit port-file)
    (write-port-to-file! port-file (:port cfg))
    (http-kit/run-server middleware cfg))
  :stop (http-server))
