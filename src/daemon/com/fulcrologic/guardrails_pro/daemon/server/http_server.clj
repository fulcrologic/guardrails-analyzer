(ns com.fulcrologic.guardrails-pro.daemon.server.http-server
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.guardrails-pro.daemon.server.config :refer [config]]
    [com.fulcrologic.guardrails-pro.daemon.server.middleware :refer [middleware]]
    [mount.core :refer [defstate]]
    [org.httpkit.server :as http-kit]
    [taoensso.timbre :as log]))

(defn upsearch-file
  [^java.io.File start-dir port-file-name]
  (loop [dir start-dir]
    (let [config-file (io/file dir "guardrails.edn")]
      (if (.exists config-file)
        (io/file dir ".guardrails-pro" port-file-name)
        (if-let [parent (.getParentFile dir)]
          (recur parent)
          (throw (ex-info "Failed to find project configuration!"
                   {:start-dir start-dir})))))))

(defn write-port-to-file! [file port]
  (io/make-parents file)
  (spit file port))

(defstate http-server
  :start
  (let [cfg (::http-kit/config config)]
    (log/info "Starting HTTP Server with config: " (pr-str cfg))
    (let [port-file (upsearch-file "." "daemon.port")]
      (.deleteOnExit port-file)
      (write-port-to-file! port-file (:port cfg)))
    (http-kit/run-server middleware cfg))
  :stop (http-server))
