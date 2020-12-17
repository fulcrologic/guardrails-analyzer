(ns com.fulcrologic.copilot.config
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]))

(defn load-config! []
  (try
    (edn/read-string
      (slurp
        (io/file (System/getProperty "user.home")
          ".copilot/config.edn")))
    (catch Exception _ {})))
