(ns com.fulcrologic.guardrails-pro.analysis.function-type-spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails.core :as gr :refer [=> | <-]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [com.fulcrologic.guardrails-pro.test-checkers :as tc]
    [fulcro-spec.core :refer [specification behavior component assertions]]
    [fulcro-spec.check :as _]))

(grp/>defn test_int->int [x]
  [int? => int?]
  (inc x))

(grp/>defn test_int->int_with-gen [x]
  [int? => int? <- (gen/fmap #(- % 1000) (gen/int))]
  (inc x))

(grp/>defn test_pos-int->int [x]
  [int? | #(pos? x) => int?]
  (inc x))

(grp/>defn test_pos-int-string->string [x y]
  [int? string? | #(and (pos? x) (seq y)) => string?]
  (str x ":" y))

(gr/>defn ->td
  "Generate a type description for a given spec"
  [name expr spec samples]
  [string? any? any? ::grp.art/samples => ::grp.art/type-description]
  {::grp.art/spec                spec
   ::grp.art/type                name
   ::grp.art/samples             samples
   ::grp.art/original-expression expr})

(specification "calculate-function-type" :integration
  (behavior "Uses the function's return spec to generate the type description"
    (let [td (grp.fnt/calculate-function-type (grp.art/build-env)
               `test_int->int
               [(->td "int?" 42 int? #{42})])]
      (assertions
        "Has the return type described in the function"
        td =check=> (_/embeds?*
                      #::grp.art{:spec (_/equals?* int?)
                                 :type "int?"})
        "Has samples generated from that spec"
        td =check=> (_/in* [::grp.art/samples]
                      (_/is?* seq)
                      (_/every?*
                        (_/is?* int?))))))
  (behavior "If spec'ed to have a custom generator"
    (assertions
      "Has samples generated from the custom generator"
      (grp.fnt/calculate-function-type (grp.art/build-env)
        `test_int->int_with-gen
        [(->td "int?" 42 int? #{42})])
      =check=> (_/in* [::grp.art/samples]
                 (_/is?* seq)
                 (_/every?*
                   (_/is?* neg-int?)))))
  (behavior "Verifies the argtypes based on the arglist specs"
    (assertions
      (tf/capture-errors grp.fnt/calculate-function-type (grp.art/build-env)
        `test_pos-int->int
        [(->td "int?" 'x int? #{"3" 22})])
      =check=> (_/all*
                 (tc/of-length?* 1)
                 (_/seq-matches?*
                   [(_/embeds?*
                      {::grp.art/original-expression 'x
                       ::grp.art/actual {::grp.art/failing-samples #{"3"}}
                       ::grp.art/expected {::grp.art/spec (_/equals?* int?)
                                           ::grp.art/type "int?"}
                       ::grp.art/problem-type :error/function-argument-failed-spec
                       ::grp.art/message-params {:argument-number 1}})]))))
  (behavior "If spec'ed to have argument predicates"
    (behavior "Returns any errors"
      (assertions
        (tf/capture-errors grp.fnt/calculate-function-type (grp.art/build-env)
          `test_pos-int->int
          [(->td "int?" 'x int? #{3 -42})])
        =check=> (_/all*
                   (tc/of-length?* 1)
                   (_/seq-matches?*
                     [(_/embeds?*
                        {::grp.art/original-expression '(x)
                         ::grp.art/actual {::grp.art/failing-samples #{-42}}
                         ::grp.art/expected {::grp.art/spec (_/is?* fn?)}
                         ::grp.art/problem-type :error/function-arguments-failed-predicate})]))
        (tf/capture-errors grp.fnt/calculate-function-type (grp.art/build-env)
          `test_pos-int-string->string
          [(->td "int?" 'x int? #{3 -42})
           (->td "string?" 'y string? #{"77" "88"})])
        =check=> (_/all*
                   (tc/of-length?* 1)
                   (_/seq-matches?*
                     [(_/embeds?*
                        {::grp.art/original-expression '(x y)
                         ::grp.art/actual {::grp.art/failing-samples #{-42 "88"}}
                         ::grp.art/expected {::grp.art/spec (_/is?* fn?)}
                         ::grp.art/problem-type :error/function-arguments-failed-predicate})]))))))

(s/def :NS/foo keyword?)
(s/def ::foo int?)
(s/def ::bar string?)

(specification "destructure*"
  (let [test-env (grp.art/build-env)
        test-td {::grp.art/type "test type desc"}]
    (assertions
      "simple symbol"
      (grp.fnt/destructure! test-env 'foo test-td)
      => {'foo (assoc test-td
                 ::grp.art/original-expression 'foo)})
    (component "vector destructuring"
      (assertions
        "returns the symbol used to bind to the entire collection"
        (grp.fnt/destructure! test-env '[foo bar :as coll] test-td)
        => {'coll (assoc test-td ::grp.art/original-expression '[foo bar :as coll])}
        "ignores all other symbols"
        (grp.fnt/destructure! test-env '[foo bar] test-td)
        => {}))
    (component "map destructuring"
      (assertions
        "simple keyword"
        (-> (grp.fnt/destructure! test-env '{foo ::foo} test-td)
          (get-in ['foo ::grp.art/spec]))
        => (s/get-spec ::foo)
        (-> (grp.fnt/destructure! test-env '{foo ::foo} test-td)
          (get-in ['foo ::grp.art/samples]))
        =check=> (_/every?* (_/is?* int?))
        "if the keyword does not have a spec it returns no entry for it"
        (grp.fnt/destructure! test-env '{foo :ERROR} test-td)
        => {})
      (component ":as binding"
        (assertions
          (grp.fnt/destructure! test-env '{:as foo} test-td)
          => {'foo (assoc test-td ::grp.art/original-expression 'foo)}
          (grp.fnt/destructure! test-env '{:ERROR/as foo} test-td)
          => {}))
      (component "keys destructuring"
        (assertions
          "not namespaced keywords are ignored"
          (grp.fnt/destructure! test-env '{:keys [foo]} test-td)
          => {}
          "can lookup specs by namespace"
          (-> (grp.fnt/destructure! test-env '{:NS/keys [foo]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => (s/get-spec :NS/foo)
          (-> (grp.fnt/destructure! test-env '{:NS/keys [foo]} test-td)
            (get-in ['foo ::grp.art/samples]))
          =check=> (_/every?* (_/is?* keyword?))
          (-> (grp.fnt/destructure! test-env '{::keys [foo]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => (s/get-spec ::foo)
          (-> (grp.fnt/destructure! test-env '{::keys [foo]} test-td)
            (get-in ['foo ::grp.art/samples]))
          =check=> (_/every?* (_/is?* int?))
          (-> (grp.fnt/destructure! test-env '{::keys [foo bar]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => (s/get-spec ::foo)
          (-> (grp.fnt/destructure! test-env '{::keys [foo bar]} test-td)
            (get-in ['bar ::grp.art/spec]))
          => (s/get-spec ::bar)
          "ignores symbol if it has no spec"
          (grp.fnt/destructure! test-env '{:FAKE/keys [foo]} test-td)
          => {})))))
