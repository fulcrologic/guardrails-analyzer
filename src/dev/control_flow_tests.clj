(ns control-flow-tests
  "Tests to verify that control flow constructs work with path-based analysis.

  Most control flow constructs (cond, when, and, or, etc.) macroexpand to if,
  so they automatically benefit from path-based analysis."
  (:require
    [com.fulcrologic.guardrails-analyzer.analysis.analyzer :as ana]
    [com.fulcrologic.guardrails-analyzer.analysis.spec :as cp.spec]
    [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
    [com.fulcrologic.guardrails-analyzer.ui.problem-formatter :as formatter]))

(defn test-analysis
  "Helper to analyze a form and return problems"
  [form]
  (cp.spec/with-empty-cache
    (fn [_]
      (let [env (cp.art/build-env {:current-file "test.clj"})]
        (ana/analyze! env form)
        (::cp.art/problems @env)))
    nil))

(defn format-error
  "Helper to format an error message"
  [error]
  (when error
    (::cp.art/message (formatter/format-problem error))))

(comment
  ;; ===== TEST 1: COND with multiple branches =====
  (println "\n=== TEST 1: COND ===")
  (let [form     '(let [x (cond
                            (pos? n) :positive
                            (neg? n) :negative
                            :else :zero)]
                    x)
        ;; In a real test we'd wrap this in >defn with a spec
        ;; For now, just verify it analyzes without errors
        problems (test-analysis form)]
    (println "Problems found:" (count problems))
    (doseq [p problems]
      (println "  -" (::cp.art/problem-type p))))

  ;; ===== TEST 2: WHEN (expands to if) =====
  (println "\n=== TEST 2: WHEN ===")
  (let [form     '(let [x (when (pos? n)
                            (* n 2))]
                    x)
        problems (test-analysis form)]
    (println "Problems found:" (count problems))
    (doseq [p problems]
      (println "  -" (::cp.art/problem-type p))))

  ;; ===== TEST 3: WHEN-NOT =====
  (println "\n=== TEST 3: WHEN-NOT ===")
  (let [form     '(let [x (when-not (zero? n)
                            (/ 100 n))]
                    x)
        problems (test-analysis form)]
    (println "Problems found:" (count problems))
    (doseq [p problems]
      (println "  -" (::cp.art/problem-type p))))

  ;; ===== TEST 4: AND (expands to nested if) =====
  (println "\n=== TEST 4: AND ===")
  (let [form     '(let [x (and (pos? n) (even? n))]
                    x)
        problems (test-analysis form)]
    (println "Problems found:" (count problems))
    (doseq [p problems]
      (println "  -" (::cp.art/problem-type p))))

  ;; ===== TEST 5: OR (expands to nested if) =====
  (println "\n=== TEST 5: OR ===")
  (let [form     '(let [x (or (zero? n) (neg? n))]
                    x)
        problems (test-analysis form)]
    (println "Problems found:" (count problems))
    (doseq [p problems]
      (println "  -" (::cp.art/problem-type p))))

  ;; ===== TEST 6: Nested COND =====
  (println "\n=== TEST 6: Nested COND ===")
  (let [form     '(let [x (cond
                            (pos? n) (cond
                                       (even? n) :pos-even
                                       :else :pos-odd)
                            (neg? n) :negative
                            :else :zero)]
                    x)
        problems (test-analysis form)]
    (println "Problems found:" (count problems))
    (doseq [p problems]
      (println "  -" (::cp.art/problem-type p))))

  ;; ===== TEST 7: IF-LET (expands to let + if) =====
  (println "\n=== TEST 7: IF-LET ===")
  (let [form     '(let [x (if-let [val (pos? n)]
                            val
                            false)]
                    x)
        problems (test-analysis form)]
    (println "Problems found:" (count problems))
    (doseq [p problems]
      (println "  -" (::cp.art/problem-type p))))

  ;; ===== TEST 8: WHEN-LET =====
  (println "\n=== TEST 8: WHEN-LET ===")
  (let [form     '(let [x (when-let [val (pos? n)]
                            (* val 2))]
                    x)
        problems (test-analysis form)]
    (println "Problems found:" (count problems))
    (doseq [p problems]
      (println "  -" (::cp.art/problem-type p))))

  (println "\n✓ All control flow tests complete!"))

;; Run all tests
(comment
  (require '[control-flow-tests])
  (in-ns 'control-flow-tests)
  ;; Then evaluate the comment block above
  )
