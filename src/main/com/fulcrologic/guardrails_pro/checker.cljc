(ns com.fulcrologic.guardrails-pro.checker
  (:require
    [clojure.test.check.generators]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn >fn =>]]
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

#_(>defn -if [x] [int? => int?]
  (if (even? x)
    (str "EVEN:" x)
    (str "ODD:" x)))

#_(>defn -doseq [x] [int? => int?]
  (doseq [i (range 10) :let [X 666]]
    (prn (+ i x))))

(>defn -map [x] [int? => int?]
  (map (>fn [n] [int? => string?] (str "n="n"x="x)) (range 10)))
