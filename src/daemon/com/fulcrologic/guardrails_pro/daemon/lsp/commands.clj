(ns com.fulcrologic.guardrails-pro.daemon.lsp.commands
  (:require
    [com.fulcrologic.guardrails-pro.daemon.server.checkers :refer [notify-checkers!]]
    [com.fulcrologic.guardrails-pro.daemon.reader :as reader]
    [taoensso.timbre :as log]))

(def foo ::log/bar)

(defn check! [path]
  (log/debug "commands/check!" path)
  ;; NOTE: currently always cljs
  (let [forms (reader/read-file path)]
    (notify-checkers! :check!
      {:forms forms})))

(def commands
  {"check!" check!})
