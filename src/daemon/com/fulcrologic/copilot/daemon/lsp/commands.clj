(ns com.fulcrologic.copilot.daemon.lsp.commands
  (:require
    [com.fulcrologic.copilot.daemon.server.checkers :as daemon.check]
    [com.fulcrologic.copilot.daemon.server.websockets :refer [websockets]]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defn check-file! [path opts]
  (log/debug "lsp.commands/check-file!" path opts)
  (daemon.check/check-file! websockets path opts))

(defn check-root-form! [path line opts]
  (log/debug "lsp.commands/check-root-form!" path line opts)
  (daemon.check/check-root-form! websockets path line opts))

(def commands
  {"check-file!"      check-file!
   "check-root-form!" check-root-form!})
