(ns com.fulcrologic.guardrails-pro.daemon.lsp.commands
  (:require
    [com.fulcrologic.guardrails-pro.daemon.server.checkers :as daemon.check]
    [com.fulcrologic.guardrails-pro.daemon.server.websockets :refer [websockets]]
    [taoensso.timbre :as log]))

(defn check-file! [path]
  (log/debug "lsp.commands/check-file!" path)
  (daemon.check/check-file! websockets path))

(defn check-root-form! [path line]
  (log/debug "lsp.commands/check-root-form!" path line)
  (daemon.check/check-root-form! websockets path line))

(def commands
  {"check-file!" check-file!
   "check-root-form!" check-root-form!})
