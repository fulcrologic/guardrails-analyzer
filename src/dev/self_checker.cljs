(ns self-checker
  (:require
    [com.fulcrologic.guardrails-pro.runtime.reporter :as reporter]
    [com.fulcrologic.guardrails-pro.interpreter :as grp.intrp]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]))


(defn init []
  (reporter/start! true)
  (grp.intrp/check-all!))

(defn refresh []
  (reporter/hot-reload!)
  (grp.intrp/check-all!)
  (reporter/report-problems! @grp.art/problems))
