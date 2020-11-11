(ns com.fulcrologic.guardrails-pro.checkers.browser
  (:require
    [com.fulcrologic.guardrails-pro.ui.checker :as checker]
    [taoensso.tufte :as prof]))

(prof/add-basic-println-handler! {})

(defn start! [opts]
  (checker/start! opts))

(defn reload! []
  (checker/hot-reload!))
