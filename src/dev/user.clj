(ns user
  (:require
    [clojure.tools.namespace.repl :as tools-ns]
    [taoensso.timbre :as log]))

(apply tools-ns/set-refresh-dirs
  (log/spy :info :refresh-dirs
    (cond-> ["src/main"]
      (System/getProperty "daemon") (conj "src/daemon")
      (System/getProperty "dev")    (conj "src/dev")
      (System/getProperty "test")   (conj "src/test"))))
