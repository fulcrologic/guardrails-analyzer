(ns com.fulcrologic.guardrails-pro.checker
  (:require
    [clojure.test.check.generators]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn >fn =>]]
    [com.fulcrologic.guardrails-pro.ftags.clojure-core]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.forms :as grp.forms]
    [taoensso.timbre :as log]))

(defn check!
  ([msg] (check! (grp.art/build-env) msg))
  ([env {:keys [forms file NS]}]
   (let [env (-> env
               (assoc ::grp.art/checking-file file)
               (assoc ::grp.art/current-ns NS))]
     (grp.art/clear-problems! file)
     (doseq [form (grp.forms/interpret forms)]
       (try (grp.ana/analyze! env form)
         (catch #? (:clj Throwable :cljs :default) t
           ;; TODO: report error
           (log/error t "Failed to analyze form:" form)))))))

(>defn ^:pure -if [x] [int? => int?]
  (if (even? x)
    (str "EVEN:" x)
    (str "ODD:" x)))

(>defn -example [x] [int? => int?]
  (-if x))
