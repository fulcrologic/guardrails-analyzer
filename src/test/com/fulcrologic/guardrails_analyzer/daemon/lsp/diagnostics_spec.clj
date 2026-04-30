(ns com.fulcrologic.guardrails-analyzer.daemon.lsp.diagnostics-spec
  "Behavioral tests for the LSP diagnostics layer.

  Covers the conversion of Copilot's internal problem maps into
  `org.eclipse.lsp4j.Diagnostic` objects, the per-client filtering
  performed when broadcasting problems, and the error reporting helper
  invoked when no checker is available for a project.

  The Java-interop wrappers (`publish-problems-for`, `report-error!`)
  are exercised transitively: tests redefine them with `with-redefs`
  to capture invocations and verify the surrounding orchestration
  logic without spinning up a real LSP client.

  Template: `artifacts_spec.clj`."
  (:require
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.daemon.lsp.diagnostics :as diag]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions component specification]])
  (:import
   (org.eclipse.lsp4j Diagnostic DiagnosticSeverity)))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn- make-problem
  "Returns a minimally-valid problem map suitable for `problem->diagnostic`,
  merging any `overrides`."
  [overrides]
  (merge {::cp.art/problem-type :error/bad-thing
          ::cp.art/message      "boom"
          ::cp.art/file         "/proj/a.clj"
          ::cp.art/line-start   10
          ::cp.art/line-end     10
          ::cp.art/column-start 5
          ::cp.art/column-end   12}
         overrides))

(defn- reset-diag-state! []
  (reset! diag/client:id->info {})
  (reset! diag/client-id->open-uri {}))

;; ====================================================================
;; problem->diagnostic
;; ====================================================================

(specification "problem->diagnostic - severity is derived from problem-type namespace"
               (assertions
                "an :error/* problem yields DiagnosticSeverity/Error"
                (.getSeverity ^Diagnostic
                 (diag/problem->diagnostic
                  (make-problem {::cp.art/problem-type :error/bad-return-value})))
                => DiagnosticSeverity/Error

                "a :warning/* problem yields DiagnosticSeverity/Warning"
                (.getSeverity ^Diagnostic
                 (diag/problem->diagnostic
                  (make-problem {::cp.art/problem-type :warning/cannot-check})))
                => DiagnosticSeverity/Warning

                "an :info/* problem yields DiagnosticSeverity/Information"
                (.getSeverity ^Diagnostic
                 (diag/problem->diagnostic
                  (make-problem {::cp.art/problem-type :info/note})))
                => DiagnosticSeverity/Information

                "a :hint/* problem yields DiagnosticSeverity/Hint"
                (.getSeverity ^Diagnostic
                 (diag/problem->diagnostic
                  (make-problem {::cp.art/problem-type :hint/binding-type-info})))
                => DiagnosticSeverity/Hint

                "an unknown namespace defaults to DiagnosticSeverity/Error"
                (.getSeverity ^Diagnostic
                 (diag/problem->diagnostic
                  (make-problem {::cp.art/problem-type :other/something})))
                => DiagnosticSeverity/Error))

(specification "problem->diagnostic - converts 1-based line/column to 0-based LSP range"
               (let [diagnostic ^Diagnostic
                     (diag/problem->diagnostic
                      (make-problem {::cp.art/line-start   10
                                     ::cp.art/line-end     12
                                     ::cp.art/column-start 5
                                     ::cp.art/column-end   20}))
                     rng        (.getRange diagnostic)
                     start      (.getStart rng)
                     end        (.getEnd rng)]
                 (assertions
                  "start line is decremented (10 -> 9)"
                  (.getLine start) => 9

                  "start column is decremented (5 -> 4)"
                  (.getCharacter start) => 4

                  "end line is decremented (12 -> 11)"
                  (.getLine end) => 11

                  "end column is decremented (20 -> 19)"
                  (.getCharacter end) => 19)))

(specification "problem->diagnostic - message and source"
               (assertions
                "passes ::cp.art/message through verbatim"
                (.getMessage ^Diagnostic
                 (diag/problem->diagnostic
                  (make-problem {::cp.art/message "expected number, got string"})))
                => "expected number, got string"

                "preserves a path-based message string exactly (single failing path)"
                (.getMessage ^Diagnostic
                 (diag/problem->diagnostic
                  (make-problem {::cp.art/message
                                 "The Return spec is boolean?, but it is possible to return a value like 42 when (< 8 n 11) → else"})))
                => "The Return spec is boolean?, but it is possible to return a value like 42 when (< 8 n 11) → else"

                "preserves a multi-path failing-path message verbatim, including the unicode arrows"
                (.getMessage ^Diagnostic
                 (diag/problem->diagnostic
                  (make-problem {::cp.art/message
                                 "The Return spec is number?, but it is possible to return a value like \"bad\", 42 on 2 paths:\n  • (pos? x) → then\n  • (pos? x) → else"})))
                => "The Return spec is number?, but it is possible to return a value like \"bad\", 42 on 2 paths:\n  • (pos? x) → then\n  • (pos? x) → else"

                "tags the diagnostic source as guardrails.analyzer"
                (.getSource ^Diagnostic
                 (diag/problem->diagnostic (make-problem {})))
                => "guardrails.analyzer"))

;; ====================================================================
;; client-for-project?
;; ====================================================================

(specification "client-for-project?"
               (assertions
                "returns true when the client info's :project-dir matches"
                (diag/client-for-project? "/proj-a" ["client-1" {:project-dir "/proj-a"}])
                => true

                "returns false when the client info's :project-dir differs"
                (diag/client-for-project? "/proj-a" ["client-1" {:project-dir "/proj-b"}])
                => false

                "returns false when the client info has no :project-dir"
                (diag/client-for-project? "/proj-a" ["client-1" {}])
                => false))

;; ====================================================================
;; update-problems!  (per-client filtering / routing)
;; ====================================================================

(specification "update-problems! - publishes the open-file's problems to a matching client"
               (component "single client whose open URI matches one of the problem files"
                          (let [calls (atom [])]
                            (reset-diag-state!)
                            (reset! diag/client:id->info     {"c1" {:remote :remote-1 :project-dir "/proj"}})
                            (reset! diag/client-id->open-uri {"c1" "file:///proj/a.clj"})
                            (with-redefs [diag/publish-problems-for
                                          (fn [remote uri probs]
                                            (swap! calls conj {:remote remote :uri uri :problems probs}))]
                              (diag/update-problems!
                               {:project-dir "/proj"}
                               [{::cp.art/file "/proj/a.clj" ::cp.art/problem-type :error/x ::cp.art/message "x"}
                                {::cp.art/file "/proj/b.clj" ::cp.art/problem-type :error/y ::cp.art/message "y"}])
                              (assertions
                               "publish-problems-for is called exactly once (one matching client)"
                               (count @calls) => 1

                               "the client's open URI is forwarded as the publish target"
                               (-> @calls first :uri) => "file:///proj/a.clj"

                               "the client's :remote handle is forwarded"
                               (-> @calls first :remote) => :remote-1

                               "only the problems whose ::file matches the open file are forwarded"
                               (mapv ::cp.art/file (-> @calls first :problems)) => ["/proj/a.clj"]))))

               (component "non-map entries and entries lacking ::problem-type are excluded"
                          (let [calls (atom [])]
                            (reset-diag-state!)
                            (reset! diag/client:id->info     {"c1" {:remote :remote-1 :project-dir "/proj"}})
                            (reset! diag/client-id->open-uri {"c1" "file:///proj/a.clj"})
                            (with-redefs [diag/publish-problems-for
                                          (fn [_ _ probs] (swap! calls conj probs))]
                              (diag/update-problems!
                               {:project-dir "/proj"}
                               [{::cp.art/file "/proj/a.clj" ::cp.art/problem-type :error/x}
                                "not-a-map"
                                42
                                {::cp.art/file "/proj/a.clj" :unrelated true}])
                              (assertions
                               "the published collection contains only maps"
                               (every? map? (first @calls)) => true

                               "every published entry has a ::problem-type"
                               (every? ::cp.art/problem-type (first @calls)) => true

                               "exactly one valid problem survives the filter"
                               (count (first @calls)) => 1))))

               (component "clients in other projects are skipped"
                          (let [calls (atom [])]
                            (reset-diag-state!)
                            (reset! diag/client:id->info     {"a" {:remote :remote-a :project-dir "/proj-a"}
                                                              "b" {:remote :remote-b :project-dir "/proj-b"}})
                            (reset! diag/client-id->open-uri {"a" "file:///proj-a/x.clj"
                                                              "b" "file:///proj-b/x.clj"})
                            (with-redefs [diag/publish-problems-for
                                          (fn [remote _ _] (swap! calls conj remote))]
                              (diag/update-problems! {:project-dir "/proj-a"} [])
                              (assertions
                               "only the client whose :project-dir matches receives the publish"
                               @calls => [:remote-a]))))

               (component "a client without an open URI is skipped"
                          (let [calls (atom [])]
                            (reset-diag-state!)
                            (reset! diag/client:id->info     {"c1" {:remote :remote-1 :project-dir "/proj"}})
                            (reset! diag/client-id->open-uri {})
                            (with-redefs [diag/publish-problems-for
                                          (fn [_ _ _] (swap! calls conj :called))]
                              (diag/update-problems! {:project-dir "/proj"} [])
                              (assertions
                               "publish-problems-for is not invoked when no URI is open"
                               @calls => []))))

               (component "the open URI's path drives the per-file filter"
                          (let [calls (atom [])]
                            (reset-diag-state!)
                            (reset! diag/client:id->info     {"c1" {:remote :remote-1 :project-dir "/proj"}})
                            (reset! diag/client-id->open-uri {"c1" "file:///proj/a.clj"})
                            (with-redefs [diag/publish-problems-for
                                          (fn [_ _ probs] (swap! calls conj probs))]
                              (diag/update-problems!
                               {:project-dir "/proj"}
                               [{::cp.art/file "/proj/a.clj" ::cp.art/problem-type :error/x}
                                {::cp.art/file "/proj/a.clj" ::cp.art/problem-type :warning/y}
                                {::cp.art/file "/proj/other.clj" ::cp.art/problem-type :error/z}])
                              (assertions
                               "all problems for the open file are kept (regardless of severity)"
                               (count (first @calls)) => 2

                               "problems for unrelated files are dropped"
                               (every? #(= "/proj/a.clj" (::cp.art/file %)) (first @calls)) => true)))))

;; ====================================================================
;; report-no-checker!
;; ====================================================================

(specification "report-no-checker! - delegates to report-error! with a path-bearing message"
               (let [reported (atom [])]
                 (reset-diag-state!)
                 (reset! diag/client:id->info {"c1" {:remote :remote-a :project-dir "/proj"}})
                 (with-redefs [diag/report-error!
                               (fn [client-info msg]
                                 (swap! reported conj {:client-info client-info :msg msg}))]
                   (diag/report-no-checker! "c1" "/proj/a.clj")
                   (assertions
                    "report-error! is invoked exactly once"
                    (count @reported) => 1

                    "the looked-up client info from client:id->info is forwarded"
                    (-> @reported first :client-info) => {:remote :remote-a :project-dir "/proj"}

                    "the path is interpolated into the message"
                    (re-find #"/proj/a\.clj" (-> @reported first :msg)) => "/proj/a.clj"

                    "the message announces that no checkers were found"
                    (re-find #"Failed to find any checkers" (-> @reported first :msg))
                    => "Failed to find any checkers"))))
