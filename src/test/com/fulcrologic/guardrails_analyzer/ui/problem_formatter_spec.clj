(ns com.fulcrologic.guardrails-analyzer.ui.problem-formatter-spec
  (:require
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [com.fulcrologic.guardrails-analyzer.ui.problem-formatter :as pf
    :refer [format-problem format-problems]]
   [fulcro-spec.core :refer [assertions specification component]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "format-problems"
               (component "with a flat vector of problems"
                          (let [problems [{::cp.art/problem-type        :error/bad-return-value
                                           ::cp.art/original-expression '(foo x)
                                           ::cp.art/expected            {::cp.art/spec int? ::cp.art/type "int?"}
                                           ::cp.art/actual              {::cp.art/failing-samples [42]}}
                                          {::cp.art/problem-type        :warning/unable-to-check
                                           ::cp.art/original-expression '(bar y)}]
                                result   (format-problems problems)]
                            (assertions
                             "preserves the vector type"
                             (vector? result) => true

                             "transforms every problem that has ::problem-type"
                             (every? #(contains? % ::cp.art/message) result) => true

                             "first problem gets the bad-return-value message"
                             (::cp.art/message (first result)) =fn=> #(.contains % "Return spec")

                             "second problem gets the unable-to-check message"
                             (::cp.art/message (second result)) =fn=> #(.contains % "Could not check"))))

               (component "with nested structures"
                          (let [nested {:by-file {"a.clj" [{::cp.art/problem-type        :warning/unable-to-check
                                                            ::cp.art/original-expression '(baz)}]}}
                                result (format-problems nested)]
                            (assertions
                             "walks into nested maps and vectors to find problems"
                             (::cp.art/message (first (get-in result [:by-file "a.clj"])))
                             =fn=> #(.contains % "Could not check"))))

               (assertions
                "returns empty vector unchanged"
                (format-problems []) => []

                "returns nil unchanged"
                (format-problems nil) => nil))

(specification "format-path-condition: condition-location fallback chain"
  ;; The formatter is private but format-path-condition exercises every
  ;; branch of format-condition-loc-suffix through the trailing location
  ;; segment of the produced string.
               (let [base {::cp.art/condition-id         0
                           ::cp.art/condition-expression '(pos? x)
                           ::cp.art/determined?          true
                           ::cp.art/condition-value      true
                           ::cp.art/branch               :then}
                     with-loc #(assoc base ::cp.art/condition-location %)]
                 (assertions
                  "uses (line N, col C) when both line and column are available"
                  (pf/format-path-condition
                   (with-loc {::cp.art/line-start 7 ::cp.art/column-start 3}))
                  => "(pos? x) → then (line 7, col 3)"

                  "falls back to (line N) when only line is available (column missing)"
                  (pf/format-path-condition
                   (with-loc {::cp.art/line-start 7}))
                  => "(pos? x) → then (line 7)"

                  "falls back to (in NS/sym) when there is no line at all but NS+sym are present"
                  (pf/format-path-condition
                   (with-loc {::cp.art/sym 'foo ::cp.art/NS "my.ns"}))
                  => "(pos? x) → then (in my.ns/foo)"

                  "falls back to (in sym) when only sym is present (no NS, no line)"
                  (pf/format-path-condition
                   (with-loc {::cp.art/sym 'foo}))
                  => "(pos? x) → then (in foo)"

                  "emits no location suffix when no useful info is present"
                  (pf/format-path-condition
                   (with-loc {}))
                  => "(pos? x) → then"

                  "treats a nil :line-start the same as a missing one (does not print 'line null')"
                  (pf/format-path-condition
                   (with-loc {::cp.art/line-start nil ::cp.art/column-start nil
                              ::cp.art/sym 'g ::cp.art/NS "n"}))
                  => "(pos? x) → then (in n/g)"

                  "indeterminate conditions get the (indeterminate) suffix and still use the loc fallback"
                  (pf/format-path-condition
                   (-> base
                       (assoc ::cp.art/determined? false)
                       (dissoc ::cp.art/condition-value)
                       (assoc ::cp.art/condition-location {::cp.art/sym 'h})))
                  => "(pos? x) → then (in h) (indeterminate)")))

(specification "format-problem :error/bad-return-value uses path-loc fallback in messages"
  ;; End-to-end check that path information without line/col survives
  ;; into the formatted message instead of producing "(line null)".
               (let [problem  {::cp.art/problem-type        :error/bad-return-value
                               ::cp.art/original-expression '(foo x)
                               ::cp.art/expected            {::cp.art/spec int? ::cp.art/type "int?"}
                               ::cp.art/actual
                               {::cp.art/failing-samples ["bad"]
                                ::cp.art/failing-paths
                                [{::cp.art/conditions
                                  [{::cp.art/condition-id         0
                                    ::cp.art/condition-expression '(pos? x)
                                    ::cp.art/condition-location   {::cp.art/sym 'foo
                                                                   ::cp.art/NS  "my.ns"}
                                    ::cp.art/determined?          true
                                    ::cp.art/condition-value      true
                                    ::cp.art/branch               :then}]}]}}
                     formatted (format-problem problem)]
                 (assertions
                  "the formatted message contains the (in NS/sym) fallback rather than '(line null)'"
                  (::cp.art/message formatted) =fn=> #(.contains % "(in my.ns/foo)")
                  "the formatted message never contains the broken 'line null' rendering"
                  (boolean (re-find #"line null" (::cp.art/message formatted))) => false)))

