(ns user
  (:require
    [clojure.tools.namespace.repl :as tools-ns]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(tools-ns/disable-reload!)
(log/set-level! :fatal)

(apply tools-ns/set-refresh-dirs
  (log/spy :info :refresh-dirs
    (cond-> ["src/main"]
      (System/getProperty "daemon") (conj "src/daemon")
      (System/getProperty "dev") (conj "src/dev")
      (System/getProperty "test") (conj "src/test"))))

(when-let [cmd (System/getProperty "user.command")]
  (log/info "Running user/command:" cmd)
  (case cmd
    "development" (do (require 'development) (eval '(development/start)))
    (log/warn "Unknown command:" cmd)))

(comment
  (tools-ns/refresh)
  (require '[kaocha.repl :as k])
  (k/run-all))
