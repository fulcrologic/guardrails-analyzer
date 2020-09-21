(ns com.fulcrologic.guardrails-pro.daemon.lsp.core
  (:require
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.daemon.lsp.server :as lsp.server]
    [com.rpl.specter :as sp]
    [mount.core :refer [defstate]]
    [taoensso.timbre :as log])
  (:import
    (java.io File)
    (java.net URI)))

(defstate lsp-server
  :start (lsp.server/start-lsp)
  :stop (lsp.server/stop-lsp lsp-server))

(defn update-problems! [{:as problems ::grp.art/keys [errors warnings]}]
  (let [uri @lsp.server/currently-open-uri
        file (-> uri (URI.) (.getPath) (File.) (.getName))]
    (lsp.server/publish-problems-for uri
      (log/spy :debug :update-problems!
        (sp/select (sp/walker ::grp.art/problem-type)
          (concat
            (get-in errors [::grp.art/indexed file])
            (get-in warnings [::grp.art/indexed file])))))))
