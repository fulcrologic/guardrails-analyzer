(ns com.fulcrologic.guardrails-pro.daemon.lsp.commands
  (:require
    [com.fulcrologic.guardrails-pro.daemon.server.checkers :as daemon.check]
    [com.fulcrologic.guardrails-pro.daemon.server.websockets :refer [websockets]]
    [com.fulcrologic.guardrails-pro.logging :as log]))

(defn check-file! [path opts]
  (log/debug "lsp.commands/check-file!" path opts)
  (daemon.check/check-file! websockets path opts))

(defn check-root-form! [path line opts]
  (log/debug "lsp.commands/check-root-form!" path line opts)
  (daemon.check/check-root-form! websockets path line opts))

(def commands
  {"check-file!"      check-file!
   "check-root-form!" check-root-form!})
