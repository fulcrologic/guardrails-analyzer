(ns ^:clj-reload/no-reload user
  (:require
   [clj-reload.core :as reload]
   [com.fulcrologicpro.taoensso.timbre :as log]))

(log/set-level! :warn)

(reload/init
 {:dirs (log/spy :info :refresh-dirs
                 (cond-> ["src/main"]
                   (System/getProperty "daemon") (conj "src/daemon")
                   (System/getProperty "dev") (conj "src/dev")
                   (System/getProperty "test") (conj "src/test")))})

(when-let [cmd (System/getProperty "user.command")]
  (log/info "Running user/command:" cmd)
  (case cmd
    "development" (do (require 'development) (eval '(development/start)))
    (log/warn "Unknown command:" cmd)))

(comment
  (reload/reload)
  (require '[kaocha.repl :as k])
  (k/run-all))
