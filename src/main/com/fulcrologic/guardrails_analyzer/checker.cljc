;; Copyright (c) Fulcrologic, LLC. All rights reserved.
;;
;; Permission to use this software requires that you
;; agree to our End-user License Agreement, legally obtain a license,
;; and use this software within the constraints of the terms specified
;; by said license.
;;
;; You may NOT publish, redistribute, or reproduce this software or its source
;; code in any form (printed, electronic, or otherwise) except as explicitly
;; allowed by your license agreement..

(ns com.fulcrologic.guardrails-analyzer.checker
  (:require
   [clojure.test.check.generators]
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer :as cp.ana]
   [com.fulcrologic.guardrails-analyzer.analysis.fdefs.clojure-core]
   [com.fulcrologic.guardrails-analyzer.analysis.fdefs.clojure-spec-alpha]
   [com.fulcrologic.guardrails-analyzer.analysis.fdefs.clojure-string]
   [com.fulcrologic.guardrails-analyzer.analysis.fdefs.malli-clojure-core]
   [com.fulcrologic.guardrails-analyzer.analysis.fdefs.malli-clojure-string]
   [com.fulcrologic.guardrails-analyzer.analysis.spec :as cp.spec]
   [com.fulcrologic.guardrails-analyzer.analytics :as cp.analytics]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.forms :as cp.forms]
   [com.fulcrologic.guardrails-analyzer.prepared-check :refer [prepared-check]]
   [com.fulcrologic.guardrails-analyzer.ui.binding-formatter :refer [format-bindings]]
   [com.fulcrologic.guardrails-analyzer.ui.problem-formatter :refer [format-problems]]
   [com.fulcrologic.guardrails-analyzer.log :as log]))

(defn check-form! [env form]
  (try (cp.ana/analyze! env form)
       (catch #?(:clj Throwable :cljs :default) t
         (cp.art/record-error! env form :error/failed-to-analyze-form)
         (log/error t "Failed to analyze form:" form))))

(defn check!
  "Runs the analyzer against the forms in `msg`. `on-done` is a 1-arg fn
   invoked when analysis completes; it receives the analysis `env` (which
   carries the per-check `::cp.art/problems-buffer` and
   `::cp.art/bindings-buffer` atoms). Pass that env to
   `gather-analysis!` (or read the buffers directly) to consume the results
   for this specific call.

   Each invocation gets its own fresh buffers via `cp.art/init-buffers`, so
   concurrent `check!` calls do not see each other's problems or bindings."
  ([msg on-done]
   (log/debug "Running check command on:" (dissoc msg :forms))
   (let [env (-> (cp.art/build-env msg)
                 (cp.art/init-buffers))
         {:as msg :keys [forms file aliases refers]}
         (update msg :forms cp.forms/interpret)]
     (cp.analytics/record-usage! env msg)
     ;; Reset legacy globals so any consumers reading `@cp.art/problems`
     ;; (older tests) only observe data from this check.
     (cp.art/clear-problems! file)
     (cp.art/clear-bindings! file)
     (cp.analytics/profile ::check!
                           (cp.spec/with-empty-cache
                             #?(:cljs (fn check-forms! [[form & forms]]
                                        (if-not form (on-done env)
                                                (js/setTimeout
                                                 (fn []
                                                   (check-form! env form)
                                                   (check-forms! forms))
                                                 100)))
                                :clj  (fn [forms]
                                        (doseq [form forms]
                                          (check-form! env form))
                                        (on-done env)))
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
  (mapv encode-problem problems))

(defn gather-analysis!
  "Returns `{:problems <vec> :bindings <vec>}` formatted for transit/IDE
   consumption. When called with `env`, reads from the env-local
   per-check buffers (`::cp.art/problems-buffer` / `::cp.art/bindings-buffer`)
   so concurrent check! calls stay isolated. The 0-arg form reads the
   legacy JVM-global atoms and is retained only for back-compat with old
   call sites."
  ([]
   {:problems (-> @cp.art/problems cp.art/unwrap-meta format-problems transit-safe-problems)
    :bindings (-> @cp.art/bindings cp.art/unwrap-meta format-bindings transit-safe-problems)})
  ([env]
   {:problems (-> (cp.art/get-problems env) cp.art/unwrap-meta format-problems transit-safe-problems)
    :bindings (-> (cp.art/get-bindings env) cp.art/unwrap-meta format-bindings transit-safe-problems)}))
