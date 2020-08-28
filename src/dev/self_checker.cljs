(ns self-checker
  (:require
    [com.fulcrologic.guardrails-pro.interpreter :as grp.intrp]))

(defn init []
  (grp.intrp/check-all!))

(defn refresh []
  (grp.intrp/check-all!))
