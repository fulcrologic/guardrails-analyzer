(ns com.fulcrologic.guardrails-analyzer.ui.problem-formatter-spec
  (:require
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [com.fulcrologic.guardrails-analyzer.ui.problem-formatter :refer [format-problem format-problems]]
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
