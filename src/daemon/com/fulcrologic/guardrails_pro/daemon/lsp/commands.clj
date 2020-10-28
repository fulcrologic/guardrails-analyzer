(ns com.fulcrologic.guardrails-pro.daemon.lsp.commands
  (:require
    [com.fulcrologic.guardrails-pro.daemon.server.checkers :refer [notify-checkers!]]
    [com.fulcrologic.guardrails-pro.daemon.reader :as reader]
    [com.fulcrologic.guardrails-pro.forms :as grp.forms]
    [taoensso.timbre :as log]))

(defn check-file! [path]
  (log/debug "commands/check-file!" path)
  (let [{:keys [NS forms]} (reader/read-file path)]
    (notify-checkers! :check!
      {:forms (grp.forms/form-expression forms)
       :file  path
       :NS    NS})))

(defn root-form-at? [cursor-line ?form]
  (let [{:keys [line end-line]} (meta ?form)]
    (<= line cursor-line end-line)))

(defn check-root-form! [path line]
  (log/debug "commands/check-root-form!" path line)
  (let [{:keys [NS forms]} (reader/read-file path)]
    (notify-checkers! :check!
      {:forms (grp.forms/form-expression
                (filter (partial root-form-at? line)
                  forms))
       :file path
       :NS   NS})))

(def commands
  {"check-file!" check-file!
   "check-root-form!" check-root-form!
   ;; TODO ? force check variants (no cache)
   })
