(ns com.fulcrologic.guardrails-analyzer.concurrent-check-spec
  "Regression spec proving per-call isolation of problems and bindings.

   With the P4-2 fix in place, each `check!` call gets fresh
   `::cp.art/problems-buffer` and `::cp.art/bindings-buffer` atoms attached
   to its env via `cp.art/init-buffers`. The on-done callback receives that
   env and reads its own buffers — so concurrent calls never observe each
   other's recorded state."
  (:require
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.checker :as cp.checker]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [=> assertions specification]])
  (:import
   (java.util.concurrent CountDownLatch TimeUnit)))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn- run-concurrent-checks!
  "Runs two `cp.checker/check!` calls in parallel against distinct files
   using `future` + `deref`. Each on-done callback hits a `CountDownLatch`
   barrier so both checks have already recorded their state before either
   snapshots the per-env buffers — surfacing leakage if state is shared.

   Returns `{:result-1 ... :result-2 ...}` where each result is
   `{:problems <vec> :bindings <vec>}`. A `::timeout` value indicates the
   barrier did not release within 5s."
  [msg-1 msg-2]
  (let [latch    (CountDownLatch. 2)
        result-1 (promise)
        result-2 (promise)
        capture! (fn [out]
                   (fn [env]
                     (.countDown latch)
                     (.await latch 5 TimeUnit/SECONDS)
                     (deliver out
                              {:problems (vec (cp.art/get-problems env))
                               :bindings (vec (cp.art/get-bindings env))})))
        f1       (future (cp.checker/check! msg-1 (capture! result-1)))
        f2       (future (cp.checker/check! msg-2 (capture! result-2)))]
    @f1
    @f2
    {:result-1 (deref result-1 5000 ::timeout)
     :result-2 (deref result-2 5000 ::timeout)}))

;; With P4-2 in place this spec exercises the env-local buffers installed by
;; `cp.art/init-buffers`. record-problem!/record-binding! write to those
;; buffers (via `::cp.art/problems-buffer` / `::cp.art/bindings-buffer`) so
;; concurrent `check!` calls do not leak through any JVM-global state.
(specification "concurrent check! per-call isolation" :integration
               (let [file-1 "concurrent_spec_file1.clj"
                     file-2 "concurrent_spec_file2.clj"
                     msg-1  {:NS    "concurrent.spec.ns1"
                             :file  file-1
                             :forms ['(let [a 1] a) '(+ 1 :kw)]}
                     msg-2  {:NS    "concurrent.spec.ns2"
                             :file  file-2
                             :forms ['(let [b 2] b) '(+ 2 :other-kw)]}
                     {:keys [result-1 result-2]} (run-concurrent-checks! msg-1 msg-2)]

                 (assertions
                  "checker 1 completed within the latch timeout"
                  (not= ::timeout result-1) => true
                  "checker 2 completed within the latch timeout"
                  (not= ::timeout result-2) => true)

                 (assertions
                  "checker 1 recorded at least one problem for its own file"
                  (boolean (some #(= file-1 (::cp.art/file %)) (:problems result-1)))
                  => true

                  "checker 2 recorded at least one problem for its own file"
                  (boolean (some #(= file-2 (::cp.art/file %)) (:problems result-2)))
                  => true)

                 ;; SANITY precondition: the leakage every? checks below are
                 ;; vacuously satisfied if recorded items lack ::cp.art/file
                 ;; entirely. A P4 fix that simply suppresses ::file population
                 ;; could mask this spec's real intent without these checks.
                 (assertions
                  "every recorded problem in result-1 carries a ::cp.art/file"
                  (every? ::cp.art/file (:problems result-1)) => true

                  "every recorded problem in result-2 carries a ::cp.art/file"
                  (every? ::cp.art/file (:problems result-2)) => true

                  "every recorded binding in result-1 carries a ::cp.art/file"
                  (every? ::cp.art/file (:bindings result-1)) => true

                  "every recorded binding in result-2 carries a ::cp.art/file"
                  (every? ::cp.art/file (:bindings result-2)) => true)

                 ;; The four assertions below previously failed via the
                 ;; shared JVM-global atoms; with env-local buffers they
                 ;; now isolate per-call state so each result only carries
                 ;; its own file's records.
                 (assertions
                  "checker 1 problems do NOT leak from file 2"
                  (every? #(= file-1 (::cp.art/file %)) (:problems result-1))
                  => true

                  "checker 2 problems do NOT leak from file 1"
                  (every? #(= file-2 (::cp.art/file %)) (:problems result-2))
                  => true

                  "checker 1 bindings do NOT leak from file 2"
                  (every? #(= file-1 (::cp.art/file %)) (:bindings result-1))
                  => true

                  "checker 2 bindings do NOT leak from file 1"
                  (every? #(= file-2 (::cp.art/file %)) (:bindings result-2))
                  => true)))
