(ns self-checker
  (:require
    [com.fulcrologic.guardrails-pro.ui.reporter :as reporter]
    [taoensso.tufte :as prof]))

(prof/add-basic-println-handler! {})

(defn init []
  (reporter/start! true))

(defn refresh []
  (reporter/hot-reload!))
