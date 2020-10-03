(ns com.fulcrologic.guardrails-pro.daemon.lsp.commands
  (:require
    [com.fulcrologic.guardrails-pro.daemon.server.checkers :refer [notify-checkers!]]
    [com.fulcrologic.guardrails-pro.daemon.reader :as reader]
    [com.fulcrologic.guardrails-pro.forms :as grp.forms]
    [taoensso.timbre :as log]))

(defn check! [path]
  (log/debug "commands/check!" path)
  ;; NOTE: path currently always cljs
  (let [{:keys [NS forms]} (reader/read-file path)]
    (notify-checkers! :check!
      {:forms (grp.forms/form-expression forms)
       :file  path
       :NS    NS})))

(def commands
  {"check!" check!})
