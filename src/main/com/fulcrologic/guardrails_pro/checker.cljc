(ns com.fulcrologic.guardrails-pro.checker
  (:require
    com.fulcrologic.guardrails-pro.ftags.clojure-core
    [clojure.test.check.generators]
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.forms :as grp.forms]
    [com.fulcrologic.guardrails-pro.ui.binding-formatter :refer [format-bindings]]
    [com.fulcrologic.guardrails-pro.ui.problem-formatter :refer [format-problems]]
    [com.fulcrologic-pro.com.rpl.specter :as $]
    [com.fulcrologic.guardrails-pro.logging :as log]
    [com.fulcrologic.guardrails-pro.analytics :as grp.analytics]))

(defn check-form! [env form]
  (try (grp.ana/analyze! env form)
       (catch #?(:clj Throwable :cljs :default) t
         (grp.art/record-error! env form :error/failed-to-analyze-form)
         (log/error t "Failed to analyze form:" form))))

(defn check!
  ([msg cb] (check! (grp.art/build-env) msg cb))
  ([env {:as msg :keys [forms file NS]} cb]
   (let [on-done (fn []
                   (grp.analytics/report-analytics!)
                   (cb))
         env (-> env
               (assoc ::grp.art/checking-file file)
               (assoc ::grp.art/current-ns NS))]
     (grp.art/clear-problems! file)
     (grp.art/clear-bindings! file)
     (grp.spec/with-cache {}
       #?(:cljs (fn check-forms! [[form & forms]]
                  (if-not form (on-done)
                    (js/setTimeout
                      (fn []
                        (check-form! env form)
                        (check-forms! forms))
                      100)))
          :clj  (fn [forms]
                  (doseq [form forms]
                    (check-form! env form))
                  (on-done)))
       (grp.forms/interpret forms)))))

(defonce to-check (atom nil))

(defn prepare-check! [msg cb]
  (reset! to-check [msg cb]))

(defn run-prepared-check! []
  (apply check! @to-check)
  (reset! to-check nil))

(defn- transit-safe-problems [problems]
  ($/transform [$/ALL]
    #(-> %
       ;; TODO: recursive-description
       (dissoc ::grp.art/actual ::grp.art/expected ::grp.art/spec
         ::grp.art/literal-value ::grp.art/original-expression)
       (assoc ::grp.art/expression (str (::grp.art/original-expression %))))
    problems))

(defn gather-analysis! []
  {:problems (-> @grp.art/problems format-problems transit-safe-problems)
   :bindings (-> @grp.art/bindings format-bindings transit-safe-problems)})
