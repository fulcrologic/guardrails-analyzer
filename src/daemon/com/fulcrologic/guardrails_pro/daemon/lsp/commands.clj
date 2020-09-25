(ns com.fulcrologic.guardrails-pro.daemon.lsp.commands
  (:require
    [com.fulcrologic.guardrails-pro.daemon.server.checkers :refer [notify-checkers!]]
    [taoensso.timbre :as log]))

(defn check! [_]
  (log/debug "commands/check!")
  (notify-checkers! :check!))

(def commands
  {"check!" check!})
