(ns com.fulcrologic.guardrails-pro.checker
  (:require
    [clojure.test.check.generators]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn >fn =>]]
    [com.fulcrologic.guardrails-pro.ftags.clojure-core]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.forms :as grp.forms]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [taoensso.timbre :as log]
    [taoensso.tufte :as prof :refer [profile p]]))

(defn check-form! [env form]
  (profile {}
    (try (p ::check-form! (grp.ana/analyze! env form))
      (catch #?(:clj Throwable :cljs :default) t
        (grp.art/record-error! env form :error/failed-to-analyze-form)
        (log/error t "Failed to analyze form:" form)))))

(defn check!
  ([msg] (check! (grp.art/build-env) msg))
  ([env {:keys [forms file NS]}]
   (let [env (-> env
               (assoc ::grp.art/checking-file file)
               (assoc ::grp.art/current-ns NS))
         forms (grp.forms/interpret forms)]
     (grp.art/clear-problems! file)
     (grp.spec/with-cache {}
       #?(:cljs ((fn check-forms! [[form & forms]]
                  (when form
                    (js/setTimeout
                      (fn []
                        (check-form! env form)
                        (check-forms! forms))
                      100)))
                 forms)
          :clj (doseq [form forms]
                 (check-form! env form)))))))

(>defn ^:pure -if [x] [int? => int?]
  (if (even? x)
    (str "EVEN:" x)
    (str "ODD:" x)))

(>defn -example [x] [int? => int?]
  (-if x))
