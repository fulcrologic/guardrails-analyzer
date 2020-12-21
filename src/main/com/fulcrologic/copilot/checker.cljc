(ns com.fulcrologic.copilot.checker
  (:require
    com.fulcrologic.copilot.ftags.clojure-core
    com.fulcrologic.copilot.ftags.clojure-string
    com.fulcrologic.copilot.ftags.clojure-spec-alpha
    [clojure.test.check.generators]
    [com.fulcrologic.copilot.analysis.analyzer :as cp.ana]
    [com.fulcrologic.copilot.analysis.spec :as cp.spec]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.prepared-check :refer [prepared-check]]
    [com.fulcrologic.copilot.forms :as cp.forms]
    [com.fulcrologic.copilot.ui.binding-formatter :refer [format-bindings]]
    [com.fulcrologic.copilot.ui.problem-formatter :refer [format-problems]]
    [com.fulcrologicpro.com.rpl.specter :as $]
    [com.fulcrologicpro.taoensso.timbre :as log]
    [com.fulcrologic.copilot.analytics :as cp.analytics]))

(defn check-form! [env form]
  (try (cp.ana/analyze! env form)
    (catch #?(:clj Throwable :cljs :default) t
      (cp.art/record-error! env form :error/failed-to-analyze-form)
      (log/error t "Failed to analyze form:" form))))

(defn check!
  ([msg on-done] (check! (cp.art/build-env) msg on-done))
  ([env msg on-done]
   (log/debug "Running check command on:" (dissoc msg :forms))
   (let [{:as msg :keys [forms file NS aliases refers]}
         (update msg :forms cp.forms/interpret)]
     (cp.analytics/record-usage! env msg)
     (cp.analytics/profile ::check!
       (let [env     (-> env
                       (assoc ::cp.art/checking-file file)
                       (assoc ::cp.art/current-ns NS)
                       (assoc ::cp.art/aliases aliases)
                       (assoc ::cp.art/refers refers))]
         (cp.art/clear-problems! file)
         (cp.art/clear-bindings! file)
         (cp.spec/with-empty-cache
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
           forms))))))

(defn prepare-check! [msg cb]
  (reset! prepared-check [msg cb]))

(defn run-prepared-check! []
  (apply check! @prepared-check)
  (reset! prepared-check nil))

(defn- transit-safe-problems [problems]
  ($/transform [$/ALL]
    #(-> %
       ;; TODO: recursive-description
       (dissoc ::cp.art/actual ::cp.art/expected ::cp.art/spec
         ::cp.art/literal-value ::cp.art/original-expression)
       (assoc ::cp.art/samples (set (map pr-str (::cp.art/samples %))))
       (assoc ::cp.art/expression
         (pr-str (cp.art/unwrap-meta (::cp.art/original-expression %)))))
    problems))

(defn gather-analysis! []
  {:problems (-> @cp.art/problems format-problems transit-safe-problems)
   :bindings (-> @cp.art/bindings format-bindings transit-safe-problems)})
