Copyright (c) Fulcrologic, LLC. All rights reserved.

Permission to use this software requires that you
agree to our End-user License Agreement, legally obtain a license,
and use this software within the constraints of the terms specified
by said license.

You may NOT publish, redistribute, or reproduce this software or its source
code in any form (printed, electronic, or otherwise) except as explicitly
allowed by your license agreement..

(ns com.fulcrologic.copilot.checker
  (:require
    clojure.test.check.generators
    com.fulcrologic.copilot.analysis.fdefs.clojure-core
    com.fulcrologic.copilot.analysis.fdefs.clojure-spec-alpha
    com.fulcrologic.copilot.analysis.fdefs.clojure-string
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
  ([msg on-done]
   (log/debug "Running check command on:" (dissoc msg :forms))
   (let [env (cp.art/build-env msg)
         {:as msg :keys [forms file aliases refers]}
         (update msg :forms cp.forms/interpret)]
     (cp.analytics/record-usage! env msg)
     (cp.art/clear-problems! file)
     (cp.art/clear-bindings! file)
     (cp.analytics/profile ::check!
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
         forms)))))

(defn prepare-check! [msg cb]
  (reset! prepared-check [msg cb]))

(defn run-prepared-check! []
  (apply check! @prepared-check)
  (reset! prepared-check nil))

(defn- encode-problem [p]
  (-> p
    ;; TODO: recursive-description
    (dissoc ::cp.art/actual ::cp.art/expected ::cp.art/spec
      ::cp.art/literal-value ::cp.art/original-expression)
    (assoc ::cp.art/samples (set (map pr-str (::cp.art/samples p))))
    (assoc ::cp.art/expression
      (pr-str (::cp.art/original-expression p)))))

(defn- transit-safe-problems [problems]
  ($/transform [$/ALL] encode-problem problems))

(defn gather-analysis! []
  {:problems (-> @cp.art/problems cp.art/unwrap-meta format-problems transit-safe-problems)
   :bindings (-> @cp.art/bindings cp.art/unwrap-meta format-bindings transit-safe-problems)})
