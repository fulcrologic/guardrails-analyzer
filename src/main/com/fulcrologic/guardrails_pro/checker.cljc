(ns com.fulcrologic.guardrails-pro.checker
  (:require
    [clojure.test.check.generators]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn =>]]
    [com.fulcrologic.guardrails-pro.ftags.clojure-core]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.forms :as grp.forms]))

(defn check!
  ([msg] (check! (grp.art/build-env) msg))
  ([env {:keys [forms file]}]
   (let [env (assoc env ::grp.art/checking-file file)]
     (grp.art/clear-problems! file)
     (doseq [form (grp.forms/interpret forms)]
       (grp.ana/analyze! env form)))))

(>defn ^:pure example [x] [int? => int?] (str "x = " x))
