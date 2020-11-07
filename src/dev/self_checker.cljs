(ns self-checker
  (:require
    [com.fulcrologic.guardrails-pro.checkers.browser :as checker]
    [taoensso.tufte :as prof]))

(prof/add-basic-println-handler! {})

(defn init []
  (checker/start! {}))

(defn reload []
  (checker/reload!))
