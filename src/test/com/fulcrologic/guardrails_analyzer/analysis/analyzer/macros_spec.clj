(ns com.fulcrologic.guardrails-analyzer.analysis.analyzer.macros-spec
  "Tests for analyzer macros: malli-registry register! no-op and the
   path-aware if-let analyzer (analyze-if-let!)."
  (:require
   [com.fulcrologic.guardrails-analyzer.analysis.analyze-test-utils :as cp.atu]
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer.dispatch :as cp.ana.disp]
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer.macros :as cp.ana.macros]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn fresh-env! []
  (cp.art/clear-problems!)
  (cp.art/clear-bindings!)
  (tf/test-env))

(defn problem-types []
  (mapv ::cp.art/problem-type @cp.art/problems))

(specification "malli registry register! is silently ignored at the top level"
  ;; Two analyze-mm defmethods (one for the `mreg` alias, one for `malli.registry`)
  ;; were added so that top-level (register! ...) calls don't generate
  ;; :info/failed-to-analyze-unknown-expression noise. Without these defmethods,
  ;; the dispatch system falls through to `unknown-expr` which records the info
  ;; problem.
               (component "fully qualified guardrails malli registry"
                          (let [env (fresh-env!)
                                td  (cp.atu/analyze-string!
                                     env "(com.fulcrologic.guardrails.malli.registry/register! :user/foo :int)")]
                            (assertions
                             "returns an empty type description (no samples, no execution-paths)"
                             (dissoc td ::cp.art/original-expression) => {}
                             "records no :info/failed-to-analyze-unknown-expression problem"
                             (some #{:info/failed-to-analyze-unknown-expression} (problem-types)) => nil
                             "records no problems at all for a valid register! call"
                             (count @cp.art/problems) => 0)))

               (component "plain malli.registry alias"
                          (let [env (fresh-env!)
                                td  (cp.atu/analyze-string!
                                     env "(malli.registry/register! :user/foo :int)")]
                            (assertions
                             "returns an empty type description"
                             (dissoc td ::cp.art/original-expression) => {}
                             "records no :info/failed-to-analyze-unknown-expression problem"
                             (some #{:info/failed-to-analyze-unknown-expression} (problem-types)) => nil
                             "records no problems at all"
                             (count @cp.art/problems) => 0)))

               (component "register! with a more complex schema argument is still ignored"
    ;; The whole form is a no-op regardless of what the schema looks like.
                          (let [env (fresh-env!)]
                            (cp.atu/analyze-string!
                             env "(com.fulcrologic.guardrails.malli.registry/register!
              :user/muser [:map [:user-id :int] [:name :string]])")
                            (assertions
                             "still produces no problems"
                             (count @cp.art/problems) => 0))))

(specification "analyze-if-let! partitions samples and records two bindings for the bind-sym"
  ;; Fix 3: the old implementation rewrote (if-let [x e] then else) to
  ;; (let [t# e] (if t# (let [x t#] then) else)), which left x bound to BOTH
  ;; truthy and falsy samples in the then-branch. Calling (inc x) on that
  ;; binding produced spurious :error/function-argument-failed-spec problems.
  ;; The new implementation partitions samples by truthiness, records two
  ;; separate bindings for x at the original location, and emits path-based
  ;; execution-paths annotated with a determined condition.
               (component "with a nilable bind-expression that yields some truthy and one nil sample"
                          (let [env (fresh-env!)
          ;; m is bound to either a map (with :user-id 5) or nil
                                env (cp.art/remember-local env 'm
                                                           {::cp.art/samples #{{:user-id 5} nil}})
                                result (cp.ana.disp/-analyze!
                                        env '(if-let [x (:user-id m)] (inc x) 0))]
                            (assertions
                             "produces no error (in particular: no inc-on-nil spec failure)"
                             (some #{:error/function-argument-failed-spec :error/value-failed-spec}
                                   (problem-types)) => nil

                             "result is path-based (uses execution-paths, not flat samples)"
                             (cp.art/path-based? result) => true

                             "records two bindings for x — one for the truthy partition, one for the nil partition"
                             (->> @cp.art/bindings
                                  (filter #(= 'x (::cp.art/original-expression %)))
                                  count) => 2

                             "the truthy binding samples are exactly the truthy partition (no nils)"
                             (->> @cp.art/bindings
                                  (filter #(= 'x (::cp.art/original-expression %)))
                                  (map ::cp.art/samples)
                                  (filter #(some identity %))
                                  first
                                  (some nil?)) => nil

                             "the falsy binding samples contain nil (the falsy partition)"
                             (->> @cp.art/bindings
                                  (filter #(= 'x (::cp.art/original-expression %)))
                                  (map ::cp.art/samples)
                                  (some #(contains? % nil))) => true

                             "the truthy partition for x contains 5 (the actual truthy value of (:user-id m))"
                             (->> @cp.art/bindings
                                  (filter #(= 'x (::cp.art/original-expression %)))
                                  (map ::cp.art/samples)
                                  (some #(contains? % 5))) => true)))

               (component "with an entirely truthy bind-expression"
                          (let [env (fresh-env!)
                                env (cp.art/remember-local env 'm
                                                           {::cp.art/samples #{{:user-id 5} {:user-id 7}}})
                                _   (cp.ana.disp/-analyze!
                                     env '(if-let [x (:user-id m)] (inc x) 0))]
                            (assertions
                             "records exactly ONE binding for x because there is no falsy partition"
                             (->> @cp.art/bindings
                                  (filter #(= 'x (::cp.art/original-expression %)))
                                  count) => 1

                             "the binding for x contains the truthy values"
                             (->> @cp.art/bindings
                                  (filter #(= 'x (::cp.art/original-expression %)))
                                  (mapcat ::cp.art/samples)
                                  set) => #{5 7})))

               (component "execution paths from analyze-if-let! are annotated with a determined condition"
                          (let [env (fresh-env!)
                                env (cp.art/remember-local env 'm
                                                           {::cp.art/samples #{{:user-id 5} nil}})
                                result (cp.ana.disp/-analyze!
                                        env '(if-let [x (:user-id m)] (inc x) 0))
                                paths (::cp.art/execution-paths result)
                                conds (mapcat ::cp.art/conditions paths)]
                            (assertions
                             "every condition on a path is determined (we partitioned the samples)"
                             (every? ::cp.art/determined? conds) => true

                             "branch annotations include :then for the truthy branch"
                             (boolean (some #(= :then (::cp.art/branch %)) conds)) => true

                             "the path-bindings for x in the then-path do not contain nil"
                             (let [then-paths (filter (fn [p]
                                                        (some #(= :then (::cp.art/branch %))
                                                              (::cp.art/conditions p)))
                                                      paths)]
                               (boolean (some #(contains? (get-in % [::cp.art/path-bindings 'x]) nil)
                                              then-paths))) => false))))
