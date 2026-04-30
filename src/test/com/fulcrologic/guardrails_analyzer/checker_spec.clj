(ns com.fulcrologic.guardrails-analyzer.checker-spec
  (:require
   [com.fulcrologic.guardrails-analyzer.analysis.analyze-test-utils :as cp.atu]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.checker :as cp.checker]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.check :as _]
   [fulcro-spec.core :refer [assertions specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn test:gather-analysis! [env x]
  ;; `tf/test-env` returns a bare env without per-check buffers, so
  ;; record-* will write to the legacy globals; clear them first to
  ;; isolate this test from previous runs, then read globals via the
  ;; 0-arg `gather-analysis!`.
  (cp.art/clear-bindings!)
  (cp.art/clear-problems!)
  (cp.atu/analyze-string! env x)
  (cp.checker/gather-analysis!))

(specification "gather-analysis!" :integration
               (let [env (tf/test-env)]
                 (assertions
                  (test:gather-analysis! env "(let [a 1] a)")
                  =check=> (_/embeds?*
                            {:bindings
                             (_/seq-matches?*
                              [(_/embeds?* {::cp.art/spec ::_/not-found})])})
      ;; TODO: recursive-description
                  )))

(specification "transit-safe-problems via gather-analysis!" :integration
               (let [env    (tf/test-env)
                     result (test:gather-analysis! env "(+ 1 :kw)")]
                 (assertions
                  "problems is a vector"
                  (vector? (:problems result)) => true

                  "each problem has ::expression as a string"
                  (every? #(string? (::cp.art/expression %)) (:problems result)) => true

                  "each problem has ::samples as a set of strings"
                  (every? #(set? (::cp.art/samples %)) (:problems result)) => true

                  "raw ::actual is stripped from encoded problems"
                  (every? #(not (contains? % ::cp.art/actual)) (:problems result)) => true

                  "raw ::expected is stripped from encoded problems"
                  (every? #(not (contains? % ::cp.art/expected)) (:problems result)) => true)))
