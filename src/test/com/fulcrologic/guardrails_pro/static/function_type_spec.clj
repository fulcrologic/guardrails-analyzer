(ns com.fulcrologic.guardrails-pro.static.function-type-spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.function-type :as grp.fnt]
    [com.fulcrologic.guardrails.core :as gr :refer [=> | <-]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [fulcro-spec.core :refer [specification behavior component assertions =fn=> when-mocking!]]))

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

(gr/>defn type-description
  "Generate a type description for a given spec"
  [name expr spec samples]
  [string? any? any? ::grp.art/samples => ::grp.art/type-description]
  {::grp.art/spec                spec
   ::grp.art/type                name
   ::grp.art/samples             samples
   ::grp.art/original-expression expr})

(defn with-mocked-errors [cb]
  (let [errors (atom #{})]
    (when-mocking!
      (grp.art/record-error! _ error) => (swap! errors conj error)
      (cb errors))))

(specification "calculate-function-type" :integration
  (behavior "Uses the function's return spec to generate the type description"
    (let [arg-type-description (type-description "int?" 42 int? #{42})
          env                  (grp.art/build-env)
          {::grp.art/keys [spec type samples]} (grp.fnt/calculate-function-type env `test_int->int [arg-type-description])]
      (assertions
        "Has the return type described in the function"
        spec => int?
        type => "int?"
        "Has samples generated from that spec"
        (boolean (seq samples)) => true
        samples =fn=> #(every? int? %))))
  (behavior "If spec'ed to have a custom generator"
    (let [arg-type-description (type-description "int?" 42 int? #{42})
          env                  (grp.art/build-env)
          {::grp.art/keys [samples]} (grp.fnt/calculate-function-type env `test_int->int_with-gen [arg-type-description])]
      (assertions
        "Has samples generated from the custom generator"
        (boolean (seq samples)) => true
        samples =fn=> #(every? neg-int? %))))
  (behavior "Verifies the argtypes based on the arglist specs"
    (with-mocked-errors
      (fn [errors]
        (grp.fnt/calculate-function-type (grp.art/build-env) `test_pos-int->int
          [(type-description "int?" 'x int? #{"3" 22})])
        (let [error (first @errors)]
          (assertions
            (count @errors) => 1
            (::grp.art/original-expression error) => 'x
            (::grp.art/actual error) => {::grp.art/failing-samples #{"3"}}
            (-> error ::grp.art/expected ::grp.art/arg-types) => ["int?"])))))
  (behavior "If spec'ed to have argument predicates"
    (behavior "Returns any errors"
      (with-mocked-errors
        (fn [errors]
          (grp.fnt/calculate-function-type (grp.art/build-env) `test_pos-int->int
            [(type-description "int?" 'x int? #{3 -42})])
          (let [error (first @errors)]
            (assertions
              (count @errors) => 1
              (::grp.art/original-expression error) => '(x)
              (::grp.art/actual error) => {::grp.art/failing-samples #{-42}}))))
      (with-mocked-errors
        (fn [errors]
          (grp.fnt/calculate-function-type (grp.art/build-env) `test_pos-int-string->string
            [(type-description "int?" 'x int? #{3 -42})
             (type-description "string?" 'y string? #{"77" "88"})])
          (let [error (first @errors)]
            (assertions
              (count @errors) => 1
              (::grp.art/original-expression error) => '(x y)
              (::grp.art/actual error) => {::grp.art/failing-samples #{-42 "88"}})))))))

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
        =fn=> #(every? int? %)
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
          =fn=> #(every? keyword? %)
          (-> (grp.fnt/destructure! test-env '{::keys [foo]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => (s/get-spec ::foo)
          (-> (grp.fnt/destructure! test-env '{::keys [foo]} test-td)
            (get-in ['foo ::grp.art/samples]))
          =fn=> #(every? int? %)
          (-> (grp.fnt/destructure! test-env '{::keys [foo bar]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => (s/get-spec ::foo)
          (-> (grp.fnt/destructure! test-env '{::keys [foo bar]} test-td)
            (get-in ['bar ::grp.art/spec]))
          => (s/get-spec ::bar)
          "ignores symbol if it has no spec"
          (grp.fnt/destructure! test-env '{:FAKE/keys [foo]} test-td)
          => {})))))
