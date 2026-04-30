(ns com.fulcrologic.guardrails-analyzer.analysis2.analyzer-spec
  "Behavioral tests for the analysis2 analyzer namespace. Targets the small
   pure helpers (`returning`, `errors`, `with-report`, `analyze-statements`,
   `check-return-type`) and the multimethod hooks exposed by `check-form`
   (the `:literal/wrapped` and `:default` dispatch branches).

   The complex `>defn` analysis path (`analyze:>defn!`,
   `analyze-single-arity!`) and the experimental `analyze-if` /
   `fork-the-world` are intentionally NOT covered here — they require a fully
   populated function registry plus argument-binding machinery that lives
   outside this namespace and is exercised by the integration tests."
  (:require
   [clojure.spec.alpha :as s]
   [com.fulcrologic.guardrails-analyzer.analysis2.analyzer :as sut]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

;; A registered spec used by the "qualified-keyword with a registered spec"
;; specification. Defined at top level so registration happens once at load.
(s/def ::registered-leaf int?)

(defn- base-env
  "A real env produced by build-env with the default test fdef registries loaded.
   `build-env` already wires both spec implementations onto the env."
  []
  (cp.art/build-env {:NS "test.ns" :file "test.clj"}))

(defn- meta-wrap
  "Builds a meta-wrapper map of the shape that `check-form :literal/wrapped`
   expects (the same shape produced by the analyzer's reader)."
  [kind value]
  {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
   :kind                                              kind
   :value                                             value})

;; ============================================================================
;; returning
;; ============================================================================

(specification "returning"
               (let [env {::pre-existing :keep-me}
                     td  {::cp.art/samples #{42} ::cp.art/spec int?}]
                 (assertions
                  "stores the type descriptor as the env's ::expression-result"
                  (::sut/expression-result (sut/returning env td)) => td

                  "does not disturb other env keys"
                  (::pre-existing (sut/returning env td)) => :keep-me

                  "overwrites a prior ::expression-result"
                  (::sut/expression-result
                   (sut/returning (assoc env ::sut/expression-result :old) td)) => td)))

;; ============================================================================
;; errors
;; ============================================================================

(specification "errors"
               (assertions
                "returns the ::errors collection from the env"
                (sut/errors {::sut/errors [{:e 1} {:e 2}]}) => [{:e 1} {:e 2}]

                "returns nil when no errors key is present"
                (sut/errors {}) => nil

                "returns an empty vector when the errors key is empty"
                (sut/errors {::sut/errors []}) => []))

;; ============================================================================
;; with-report
;; ============================================================================

(specification "with-report (4-arity: expr/level/problem-type only)"
               (let [reported (sut/with-report {} 'some-expr 1 :warning/empty-body)
                     err      (first (sut/errors reported))]
                 (assertions
                  "appends exactly one error to ::errors"
                  (count (sut/errors reported)) => 1

                  "the error records the original-expression"
                  (::cp.art/original-expression err) => 'some-expr

                  "the error records the supplied level"
                  (::cp.art/level err) => 1

                  "the error records the supplied problem-type"
                  (::cp.art/problem-type err) => :warning/empty-body

                  "no ::actual is recorded when no failure is supplied"
                  (contains? err ::cp.art/actual) => false

                  "no ::expected is recorded when no spec/type is supplied"
                  (contains? err ::cp.art/expected) => false)))

(specification "with-report (7-arity: full failure / expected metadata)"
               (let [reported (sut/with-report {} 'expr :failing-val int? "the-int" 10 :error/bad-return-value)
                     err      (first (sut/errors reported))]
                 (assertions
                  "the error records the original-expression"
                  (::cp.art/original-expression err) => 'expr

                  "the error records the supplied level and problem-type"
                  (::cp.art/level err) => 10
                  (::cp.art/problem-type err) => :error/bad-return-value

                  "the error records ::actual.failing-samples as a singleton set containing the failure value"
                  (::cp.art/actual err) => {::cp.art/failing-samples #{:failing-val}}

                  "the error records ::expected with the spec and type"
                  (::cp.art/expected err) => {::cp.art/spec int? ::cp.art/type "the-int"})))

(specification "with-report (7-arity: omits optional sections)"
               (component "with no failure value"
                          (let [reported (sut/with-report {} 'expr nil int? "the-int" 1 :info/whatever)
                                err      (first (sut/errors reported))]
                            (assertions
                             "::actual is omitted when failure is nil"
                             (contains? err ::cp.art/actual) => false

                             "::expected is still recorded when both spec and type are present"
                             (::cp.art/expected err) => {::cp.art/spec int? ::cp.art/type "the-int"})))

               (component "with no spec/type"
                          (let [reported (sut/with-report {} 'expr :v nil nil 1 :info/whatever)
                                err      (first (sut/errors reported))]
                            (assertions
                             "::actual is recorded because failure is present"
                             (::cp.art/actual err) => {::cp.art/failing-samples #{:v}}

                             "::expected is omitted when spec and type are nil"
                             (contains? err ::cp.art/expected) => false))))

(specification "with-report appends across calls"
               (let [reported (-> {}
                                  (sut/with-report 'a 1 :warning/one)
                                  (sut/with-report 'b 1 :warning/two))]
                 (assertions
                  "both errors are present, in the order they were reported"
                  (mapv ::cp.art/problem-type (sut/errors reported))
                  => [:warning/one :warning/two])))

;; ============================================================================
;; check-form :default
;; ============================================================================

(specification "check-form :default — unknown forms"
               (let [env    (base-env)
                     ;; A bare integer dispatches to :unknown (it is not seq?, symbol?,
                     ;; collection, boolean, or ifn?), which falls through to :default.
                     result (sut/check-form env 42)]
                 (assertions
                  "returns a sequence containing the env"
                  (sequential? result) => true

                  "returns exactly one env"
                  (count result) => 1

                  "the returned env is the input env (no errors recorded)"
                  (first result) => env

                  "no errors were appended"
                  (sut/errors (first result)) => nil)))

;; ============================================================================
;; check-form :literal/wrapped
;; ============================================================================

(specification "check-form :literal/wrapped — plain literal value"
               (let [env     (base-env)
                     wrapped (meta-wrap :number 42)
                     [out]   (sut/check-form env wrapped)
                     td      (::sut/expression-result out)]
                 (assertions
                  "returns a single-env sequence"
                  (count (sut/check-form env wrapped)) => 1

                  "places a type-descriptor in ::expression-result"
                  (some? td) => true

                  "the descriptor's samples contain the literal value"
                  (::cp.art/samples td) => #{42}

                  "the descriptor records the wrapped form as the original-expression"
                  (::cp.art/original-expression td) => wrapped

                  "no warnings are appended for a plain (non-keyword) literal"
                  (sut/errors out) => nil)))

(specification "check-form :literal/wrapped — qualified-keyword without a registered spec"
               (let [env     (base-env)
                     wrapped (meta-wrap :keyword ::no-such-spec)
                     [out]   (sut/check-form env wrapped)
                     errs    (sut/errors out)]
                 (assertions
                  "appends exactly one warning for the missing spec"
                  (count errs) => 1

                  "the warning's problem-type is :warning/qualified-keyword-missing-spec"
                  (::cp.art/problem-type (first errs)) => :warning/qualified-keyword-missing-spec

                  "the warning records the wrapped form as its original-expression"
                  (::cp.art/original-expression (first errs)) => wrapped

                  "the type descriptor is still produced (samples carry the keyword value)"
                  (::cp.art/samples (::sut/expression-result out)) => #{::no-such-spec})))

(specification "check-form :literal/wrapped — qualified-keyword with a registered spec"
               (let [env     (base-env)
                     wrapped (meta-wrap :keyword ::registered-leaf)
                     [out]   (sut/check-form env wrapped)]
                 (assertions
                  "no missing-spec warning is recorded when the spec exists"
                  (sut/errors out) => nil

                  "still produces a type descriptor with the keyword as its sample"
                  (::cp.art/samples (::sut/expression-result out))
                  => #{::registered-leaf})))

;; ============================================================================
;; analyze-statements
;; ============================================================================

(specification "analyze-statements — empty body"
               (let [env    (base-env)
                     result (sut/analyze-statements env [])]
                 (assertions
                  "returns a single-env sequence"
                  (count result) => 1

                  "appends a :warning/empty-body warning"
                  (mapv ::cp.art/problem-type (sut/errors (first result)))
                  => [:warning/empty-body])))

(specification "analyze-statements — body whose last form is nil"
               (let [env    (base-env)
                     result (sut/analyze-statements env [nil])]
                 (assertions
                  "still triggers the empty-body warning when the last form is falsy"
                  (mapv ::cp.art/problem-type (sut/errors (first result)))
                  => [:warning/empty-body])))

(specification "analyze-statements — single statement"
               (let [env     (base-env)
                     wrapped (meta-wrap :number 7)
                     [out]   (sut/analyze-statements env [wrapped])]
                 (assertions
                  "delegates to check-form on the last (only) form"
                  (::cp.art/samples (::sut/expression-result out)) => #{7}

                  "no empty-body warning is reported for a non-nil terminal form"
                  (sut/errors out) => [])))

(specification "analyze-statements — multiple statements keep only the last result"
               (let [env     (base-env)
                     first*  (meta-wrap :number 1)
                     last*   (meta-wrap :number 99)
                     [out]   (sut/analyze-statements env [first* last*])]
                 (assertions
                  "the env's ::expression-result reflects the last form's analysis"
                  (::cp.art/samples (::sut/expression-result out)) => #{99}

                  "earlier statement results are NOT used as the expression-result"
                  (contains? (::cp.art/samples (::sut/expression-result out)) 1) => false)))

;; ============================================================================
;; check-return-type
;; ============================================================================

(specification "check-return-type — sample matches return-spec"
               (let [env   (-> (base-env)
                               (sut/returning {::cp.art/samples #{1 2 3}}))
                     gspec {::cp.art/return-spec int?
                            ::cp.art/return-type "int"}
                     out   (sut/check-return-type env gspec 'my-fn)]
                 (assertions
                  "returns nil (no env, no error) when every sample is valid"
                  out => nil)))

(specification "check-return-type — at least one sample fails the return-spec"
               (let [env   (-> (base-env)
                               (sut/returning {::cp.art/samples #{"not-an-int"}}))
                     gspec {::cp.art/return-spec int?
                            ::cp.art/return-type "int"}
                     out   (sut/check-return-type env gspec 'my-fn)
                     err   (first (sut/errors out))]
                 (assertions
                  "appends exactly one error to the env"
                  (count (sut/errors out)) => 1

                  "the error's problem-type is :error/bad-return-value"
                  (::cp.art/problem-type err) => :error/bad-return-value

                  "the error records the function symbol as its original-expression"
                  (::cp.art/original-expression err) => 'my-fn

                  "the error records the failing sample under ::actual.failing-samples"
                  (::cp.art/actual err) => {::cp.art/failing-samples #{"not-an-int"}}

                  "the error records the return-spec and return-type under ::expected"
                  (::cp.art/expected err) => {::cp.art/spec int? ::cp.art/type "int"}

                  "the error level is 10 (error severity)"
                  (::cp.art/level err) => 10)))
