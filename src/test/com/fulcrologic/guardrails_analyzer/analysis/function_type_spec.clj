(ns com.fulcrologic.guardrails-analyzer.analysis.function-type-spec
  "Behavioral tests for `check-return-type!`, which validates the return value of
   a function (or branch thereof) against the gspec's return-spec.

   The function under test is path-aware: when given a path-based type description
   (one with `::cp.art/execution-paths`), it validates each execution path
   independently and records `:error/bad-return-value` annotated with the
   `::cp.art/failing-paths` that violated the spec. When given a non-path-based
   type description, it validates the flat sample set and records a single
   failing-sample.

   The behaviors covered here:
     * 3-arity delegating form pulls original-expression from the td
     * single path success -> no error
     * single path failure -> error with that path in failing-paths
     * multi-path failure -> only the failing paths are reported
     * partially-failing paths -> non-failing paths are excluded
     * nested condition attribution -> conditions chain is preserved
     * the recorded error carries expected/actual/problem-type
     * the failing-samples set is the union of failing-sample per path
     * non-path-based: all-valid samples -> no error
     * non-path-based: failing sample -> single error recorded
     * non-path-based: empty samples (no-paths fallback) -> no error
     * non-path-based: original-expression preserved on the recorded error"
  (:require
   [com.fulcrologic.guardrails-analyzer.analysis.function-type :as cp.fnt]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [=> assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn fresh-env! []
  (cp.art/clear-problems!)
  (cp.art/clear-bindings!)
  (tf/test-env))

(defn- only-problem
  "Return the lone recorded problem, asserting that exactly one was recorded."
  []
  (let [ps @cp.art/problems]
    (assert (= 1 (count ps))
            (str "Expected exactly one recorded problem, got " (count ps)))
    (first ps)))

(def int-gspec
  "A minimal gspec whose return-spec is `int?`."
  #::cp.art{:return-spec int?
            :return-type 'int})

(def string-gspec
  "A minimal gspec whose return-spec is `string?`."
  #::cp.art{:return-spec string?
            :return-type 'string})

(specification "check-return-type! 3-arity delegates to 4-arity using td's original-expression"
               (component "with a path-based td whose path has a failing sample"
                          (let [env (fresh-env!)
                                td  {::cp.art/original-expression 'my-defn-sym
                                     ::cp.art/execution-paths
                                     [{::cp.art/path-id       0
                                       ::cp.art/conditions    []
                                       ::cp.art/samples       #{"not-an-int"}
                                       ::cp.art/path-bindings {}}]}
                                _   (cp.fnt/check-return-type! env int-gspec td)
                                p   (only-problem)]
                            (assertions
                             "records exactly one problem"
                             (count @cp.art/problems) => 1
                             "uses the td's original-expression on the recorded problem"
                             (::cp.art/original-expression p) => 'my-defn-sym
                             "records :error/bad-return-value as the problem-type"
                             (::cp.art/problem-type p) => :error/bad-return-value)))

               (component "with a non-path-based td whose samples include a failing value"
                          (let [env (fresh-env!)
                                td  {::cp.art/original-expression 'inner-call
                                     ::cp.art/samples             #{"oops"}}
                                _   (cp.fnt/check-return-type! env int-gspec td)
                                p   (only-problem)]
                            (assertions
                             "uses the td's original-expression on the recorded problem"
                             (::cp.art/original-expression p) => 'inner-call))))

(specification "check-return-type! path-based branch — success cases (no error)"
               (component "single path whose samples all satisfy the return-spec"
                          (let [env (fresh-env!)
                                td  {::cp.art/execution-paths
                                     [{::cp.art/path-id       0
                                       ::cp.art/conditions    []
                                       ::cp.art/samples       #{1 2 3}
                                       ::cp.art/path-bindings {}}]}]
                            (cp.fnt/check-return-type! env int-gspec td 'caller)
                            (assertions
                             "records no problems when every sample on the only path is valid"
                             (count @cp.art/problems) => 0)))

               (component "multiple paths, every path's samples satisfy the return-spec"
                          (let [env (fresh-env!)
                                td  {::cp.art/execution-paths
                                     [{::cp.art/path-id       0
                                       ::cp.art/conditions    [{::cp.art/condition-id         0
                                                                ::cp.art/condition-expression '(pos? x)
                                                                ::cp.art/condition-location   {::cp.art/line-start 1
                                                                                               ::cp.art/column-start 1}
                                                                ::cp.art/determined?          true
                                                                ::cp.art/branch               :then}]
                                       ::cp.art/samples       #{1 2 3}
                                       ::cp.art/path-bindings {'x #{1 2 3}}}
                                      {::cp.art/path-id       1
                                       ::cp.art/conditions    [{::cp.art/condition-id         0
                                                                ::cp.art/condition-expression '(pos? x)
                                                                ::cp.art/condition-location   {::cp.art/line-start 1
                                                                                               ::cp.art/column-start 1}
                                                                ::cp.art/determined?          true
                                                                ::cp.art/branch               :else}]
                                       ::cp.art/samples       #{-1 -2}
                                       ::cp.art/path-bindings {'x #{-1 -2}}}]}]
                            (cp.fnt/check-return-type! env int-gspec td 'caller)
                            (assertions
                             "records no problems when every path's samples are all valid"
                             (count @cp.art/problems) => 0))))

(specification "check-return-type! path-based branch — single-path failure"
               (let [env  (fresh-env!)
                     path {::cp.art/path-id       0
                           ::cp.art/conditions    [{::cp.art/condition-id         0
                                                    ::cp.art/condition-expression '(pos? x)
                                                    ::cp.art/condition-location   {::cp.art/line-start 1
                                                                                   ::cp.art/column-start 1}
                                                    ::cp.art/determined?          true
                                                    ::cp.art/branch               :then}]
                           ::cp.art/samples       #{1 "bad" 3}
                           ::cp.art/path-bindings {'x #{1 "bad" 3}}}
                     td   {::cp.art/execution-paths [path]}
                     _    (cp.fnt/check-return-type! env int-gspec td 'caller-sym)
                     p    (only-problem)]
                 (assertions
                  "records exactly one problem for the failing path"
                  (count @cp.art/problems) => 1
                  "the recorded problem-type is :error/bad-return-value"
                  (::cp.art/problem-type p) => :error/bad-return-value
                  "the recorded problem records the caller's original-expression"
                  (::cp.art/original-expression p) => 'caller-sym
                  "the expected map carries the gspec's return-spec"
                  (get-in p [::cp.art/expected ::cp.art/spec]) => int?
                  "the expected map carries the gspec's return-type"
                  (get-in p [::cp.art/expected ::cp.art/type]) => 'int
                  "the failing-samples set contains the failing value"
                  (get-in p [::cp.art/actual ::cp.art/failing-samples]) => #{"bad"}
                  "the failing-paths vector contains exactly the failing path"
                  (count (get-in p [::cp.art/actual ::cp.art/failing-paths])) => 1
                  "the failing path retains its original path-id"
                  (-> p ::cp.art/actual ::cp.art/failing-paths first ::cp.art/path-id) => 0
                  "the failing path is annotated with ::cp.art/failing-sample"
                  (-> p ::cp.art/actual ::cp.art/failing-paths first ::cp.art/failing-sample) => "bad"
                  "the failing path retains its conditions chain"
                  (-> p ::cp.art/actual ::cp.art/failing-paths first ::cp.art/conditions
                      first ::cp.art/condition-expression) => '(pos? x))))

(specification "check-return-type! path-based branch — multi-path partial failure"
               (let [env       (fresh-env!)
                     good-path {::cp.art/path-id       0
                                ::cp.art/conditions    [{::cp.art/condition-id         0
                                                         ::cp.art/condition-expression '(even? n)
                                                         ::cp.art/condition-location   {::cp.art/line-start 1
                                                                                        ::cp.art/column-start 1}
                                                         ::cp.art/determined?          true
                                                         ::cp.art/branch               :then}]
                                ::cp.art/samples       #{2 4 6}
                                ::cp.art/path-bindings {'n #{2 4 6}}}
                     bad-path  {::cp.art/path-id       1
                                ::cp.art/conditions    [{::cp.art/condition-id         0
                                                         ::cp.art/condition-expression '(even? n)
                                                         ::cp.art/condition-location   {::cp.art/line-start 1
                                                                                        ::cp.art/column-start 1}
                                                         ::cp.art/determined?          true
                                                         ::cp.art/branch               :else}]
                                ::cp.art/samples       #{1 :keyword-fails-int 5}
                                ::cp.art/path-bindings {'n #{1 :keyword-fails-int 5}}}
                     td        {::cp.art/execution-paths [good-path bad-path]}
                     _         (cp.fnt/check-return-type! env int-gspec td 'caller-sym)
                     p         (only-problem)
                     f-paths   (get-in p [::cp.art/actual ::cp.art/failing-paths])]
                 (assertions
                  "records exactly one problem (a single error aggregating failing paths)"
                  (count @cp.art/problems) => 1
                  "the failing-paths vector contains only the path with a failing sample"
                  (count f-paths) => 1
                  "the surviving failing path is the originally-failing path (path-id 1)"
                  (::cp.art/path-id (first f-paths)) => 1
                  "the failing path's branch is preserved as :else"
                  (-> f-paths first ::cp.art/conditions first ::cp.art/branch) => :else
                  "the failing-samples set surfaces only the value that broke the spec"
                  (get-in p [::cp.art/actual ::cp.art/failing-samples]) => #{:keyword-fails-int})))

(specification "check-return-type! path-based branch — every path fails"
               (let [env   (fresh-env!)
                     bad-1 {::cp.art/path-id       0
                            ::cp.art/conditions    [{::cp.art/condition-id         0
                                                     ::cp.art/condition-expression '(pos? x)
                                                     ::cp.art/condition-location   {::cp.art/line-start 1
                                                                                    ::cp.art/column-start 1}
                                                     ::cp.art/determined?          true
                                                     ::cp.art/branch               :then}]
                            ::cp.art/samples       #{:bad-keyword}
                            ::cp.art/path-bindings {'x #{:bad-keyword}}}
                     bad-2 {::cp.art/path-id       1
                            ::cp.art/conditions    [{::cp.art/condition-id         0
                                                     ::cp.art/condition-expression '(pos? x)
                                                     ::cp.art/condition-location   {::cp.art/line-start 1
                                                                                    ::cp.art/column-start 1}
                                                     ::cp.art/determined?          true
                                                     ::cp.art/branch               :else}]
                            ::cp.art/samples       #{"also-bad"}
                            ::cp.art/path-bindings {'x #{"also-bad"}}}
                     td    {::cp.art/execution-paths [bad-1 bad-2]}
                     _     (cp.fnt/check-return-type! env int-gspec td 'caller-sym)
                     p     (only-problem)
                     fps   (get-in p [::cp.art/actual ::cp.art/failing-paths])]
                 (assertions
                  "records exactly one error covering both failing paths"
                  (count @cp.art/problems) => 1
                  "both paths appear in failing-paths"
                  (count fps) => 2
                  "failing-paths preserves both path ids (order-insensitive)"
                  (set (map ::cp.art/path-id fps)) => #{0 1}
                  "failing-samples is the union of every path's failing-sample"
                  (get-in p [::cp.art/actual ::cp.art/failing-samples]) => #{:bad-keyword "also-bad"}
                  "each failing path is annotated with its own ::cp.art/failing-sample"
                  (set (map ::cp.art/failing-sample fps)) => #{:bad-keyword "also-bad"})))

(specification "check-return-type! path-based branch — nested condition attribution"
  ;; A path that traversed two conditions in succession (e.g. (pos? x) -> :then,
  ;; then (even? x) -> :else) should retain BOTH conditions in its conditions
  ;; chain when surfaced as a failing path.
               (let [env    (fresh-env!)
                     nested {::cp.art/path-id       0
                             ::cp.art/conditions    [{::cp.art/condition-id         0
                                                      ::cp.art/condition-expression '(pos? x)
                                                      ::cp.art/condition-location   {::cp.art/line-start 1
                                                                                     ::cp.art/column-start 1}
                                                      ::cp.art/determined?          true
                                                      ::cp.art/branch               :then}
                                                     {::cp.art/condition-id         1
                                                      ::cp.art/condition-expression '(even? x)
                                                      ::cp.art/condition-location   {::cp.art/line-start 2
                                                                                     ::cp.art/column-start 1}
                                                      ::cp.art/determined?          true
                                                      ::cp.art/branch               :else}]
                             ::cp.art/samples       #{:not-an-int}
                             ::cp.art/path-bindings {'x #{:not-an-int}}}
                     td     {::cp.art/execution-paths [nested]}
                     _      (cp.fnt/check-return-type! env int-gspec td 'caller-sym)
                     fps    (get-in (only-problem) [::cp.art/actual ::cp.art/failing-paths])
                     f-conditions (-> fps first ::cp.art/conditions)]
                 (assertions
                  "the failing path retains both conditions in order"
                  (mapv ::cp.art/condition-expression f-conditions) => '[(pos? x) (even? x)]
                  "the outer condition is attributed to its :then branch"
                  (-> f-conditions (nth 0) ::cp.art/branch) => :then
                  "the inner condition is attributed to its :else branch"
                  (-> f-conditions (nth 1) ::cp.art/branch) => :else
                  "each condition retains its condition-id"
                  (mapv ::cp.art/condition-id f-conditions) => [0 1])))

(specification "check-return-type! non-path-based branch — success cases (no error)"
               (component "samples that all satisfy the return-spec"
                          (let [env (fresh-env!)
                                td  {::cp.art/samples #{1 2 3}}]
                            (cp.fnt/check-return-type! env int-gspec td 'caller)
                            (assertions
                             "records no problem when every sample is valid"
                             (count @cp.art/problems) => 0)))

               (component "no-paths fallback — empty samples set"
    ;; Falls into the non-path-based branch. With no samples there is nothing
    ;; to invalidate, so no error is recorded.
                          (let [env (fresh-env!)
                                td  {::cp.art/samples #{}}]
                            (cp.fnt/check-return-type! env int-gspec td 'caller)
                            (assertions
                             "records no problem when there are no samples to check"
                             (count @cp.art/problems) => 0)))

               (component "no-paths fallback — type description without ::samples key"
    ;; The :value branch of ::type-description allows everything optional, so
    ;; a td with no ::samples and no ::execution-paths is valid input.
                          (let [env (fresh-env!)
                                td  {::cp.art/spec int?}]
                            (cp.fnt/check-return-type! env int-gspec td 'caller)
                            (assertions
                             "records no problem when the td has no samples at all"
                             (count @cp.art/problems) => 0))))

(specification "check-return-type! non-path-based branch — failure cases"
               (component "samples include a value that fails the return-spec"
                          (let [env (fresh-env!)
                                td  {::cp.art/samples #{1 "bad" 3}}
                                _   (cp.fnt/check-return-type! env int-gspec td 'caller-sym)
                                p   (only-problem)]
                            (assertions
                             "records exactly one problem"
                             (count @cp.art/problems) => 1
                             "records :error/bad-return-value as the problem-type"
                             (::cp.art/problem-type p) => :error/bad-return-value
                             "the recorded original-expression matches what the caller passed"
                             (::cp.art/original-expression p) => 'caller-sym
                             "the recorded expected spec is the gspec's return-spec"
                             (get-in p [::cp.art/expected ::cp.art/spec]) => int?
                             "the recorded expected type is the gspec's return-type"
                             (get-in p [::cp.art/expected ::cp.art/type]) => 'int
                             "the failing-samples set contains exactly the offending value"
                             (get-in p [::cp.art/actual ::cp.art/failing-samples]) => #{"bad"}
                             "no ::cp.art/failing-paths key is present (this is the non-path-based branch)"
                             (contains? (::cp.art/actual p) ::cp.art/failing-paths) => false)))

               (component "different return-spec — string?"
                          (let [env (fresh-env!)
                                td  {::cp.art/samples #{42}}
                                _   (cp.fnt/check-return-type! env string-gspec td 'caller-sym)
                                p   (only-problem)]
                            (assertions
                             "the recorded expected spec follows the gspec passed in"
                             (get-in p [::cp.art/expected ::cp.art/spec]) => string?
                             "the recorded expected type follows the gspec passed in"
                             (get-in p [::cp.art/expected ::cp.art/type]) => 'string
                             "the failing-samples set contains the offending value"
                             (get-in p [::cp.art/actual ::cp.art/failing-samples]) => #{42}))))

(specification "check-return-type! path-based branch — distinguishes a path with no samples"
  ;; A path with an empty samples set has no failing-sample, and so is excluded
  ;; from failing-paths. When all paths have empty samples, no error is recorded.
               (let [env       (fresh-env!)
                     empty-pth {::cp.art/path-id       0
                                ::cp.art/conditions    []
                                ::cp.art/samples       #{}
                                ::cp.art/path-bindings {}}
                     td        {::cp.art/execution-paths [empty-pth]}]
                 (cp.fnt/check-return-type! env int-gspec td 'caller-sym)
                 (assertions
                  "records no problem when the only path has no samples to invalidate"
                  (count @cp.art/problems) => 0)))
