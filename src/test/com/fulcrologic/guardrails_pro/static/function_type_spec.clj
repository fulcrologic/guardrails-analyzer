(ns com.fulcrologic.guardrails-pro.static.function-type-spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [clojure.test :as t]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.function-type :as grp.fnt]
    [com.fulcrologic.guardrails.core :as gr :refer [=> | <-]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [fulcro-spec.core :refer [specification assertions behavior =fn=> when-mocking!]]))

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

(grp/>defn test_abs-val_pure [x]
  ^::grp.art/pure? [int? => int?]
  (if (neg? x) (- x) x))

(gr/>defn type-description
  "Generate a type description for a given spec"
  [name expr spec samples]
  [string? any? any? any? => ::grp.art/type-description]
  {::grp.art/spec                spec
   ::grp.art/type                name
   ::grp.art/samples             samples
   ::grp.art/original-expression expr})

(defn with-mocked-errors [cb]
  (let [errors (atom [])]
    (when-mocking!
      (grp.art/record-error! _ error) => (swap! errors conj error)
      (cb errors))))

(specification "calculate-function-type (non-special)"
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
            (::grp.art/actual error) => {::grp.art/failing-samples ["3"]}
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
              (::grp.art/actual error) => {::grp.art/failing-samples [-42]}))))
      (with-mocked-errors
        (fn [errors]
          (grp.fnt/calculate-function-type (grp.art/build-env) `test_pos-int-string->string
            [(type-description "int?" 'x int? #{3 -42})
             (type-description "string?" 'y string? #{"77" "88"})])
          (let [error (first @errors)]
            (assertions
              (count @errors) => 1
              (::grp.art/original-expression error) => '(x y)
              (::grp.art/actual error) => {::grp.art/failing-samples [-42 "88"]})))))))

(specification "calculate-function-type (pure)"
  (behavior "Uses the function itself to generate type information"
    (let [input-samples        #{42 -32}
          arg-type-description (type-description "int?" 42 int? input-samples)
          env                  (grp.art/build-env)
          {::grp.art/keys [samples]} (grp.fnt/calculate-function-type env `test_abs-val_pure [arg-type-description])]
      (assertions
        "The return type has new samples"
        (not= samples input-samples) => true
        (boolean (seq samples)) => true
        "The samples are transformed by the real function"
        samples =fn=> #(every? pos? %))))
  (behavior "Still checks its arguments based on their specs"
    (with-mocked-errors
      (fn [errors]
        (grp.fnt/calculate-function-type (grp.art/build-env) `test_abs-val_pure
          [(type-description "int?" 'x int? #{"3" 22})])
        (let [error (first @errors)]
          (assertions
            (::grp.art/original-expression error) => 'x
            (::grp.art/actual error) => {::grp.art/failing-samples ["3"]}
            (-> error ::grp.art/expected ::grp.art/arg-types) => ["int?"]))))))





(defn check-error? [x] (map? x))

(defn all* [& checkers]
  (fn [actual]
    (filter check-error?
        (flatten
          (map #(% actual) checkers)))))

(defn is?* [predicate]
  (fn [actual]
    (when-not (predicate actual)
      {:actual actual
       :expected predicate})))

(defn equals?* [expected]
  (fn [actual]
    (when-not (= expected actual)
      {:actual actual
       :expected expected})))

(defn valid?* [spec]
  (fn [actual]
    (when-not (s/valid? spec actual)
      {:message (s/explain-str spec actual)
       :actual actual
       :expected spec})))

(defn embeds?*
  ([expected] (embeds?* expected []))
  ([expected path]
   (fn [actual]
     (seq
       (for [[k v] expected]
         (let [actual-value (get actual k)
               path (conj path k)]
           (cond
             (map? v) #_=> ((embeds?* v path) actual-value)
             (not= actual-value v)
             #_=> {:actual actual-value
                   :expected v
                   :message (str "at path " path ":")})))))))

(defn check! [& checkers]
  (fn [actual]
    (when-let [errors ((apply all* checkers) actual)]
      (doseq [e errors]
          (t/do-report
            (assoc e :type :fail))))
    ;; silence =fn=> reporting
    true))

(specification "check!"
  (let [x-double? (fn [actual]
                    (when-not (double? (get-in actual [:x]))
                      {:actual actual
                       :expected `double?
                       :message "x was not a double"}))
        failing-checker (fn [actual]
                          (vector
                            (let [v (get-in actual [:FAKE/int])]
                              (when-not (int? v)
                                {:actual v, :expected `int?
                                 :message ":FAKE/int was not an int"}))
                            (let [v (get-in actual [:FAKE/string])]
                              (when-not (string? v)
                                {:actual v, :expected `string?
                                 :message ":FAKE/string was not an string"}))))
        data {:a 1 :b {:c 2 :d 3}}]
    (assertions
      1 => 1
      ;123  =fn=> (check! (equals?* 456))
      ;222  =fn=> (check! (is?* odd?))
      ;{}   =fn=> (check! (valid?* ::grp.art/env))
      ;data =fn=> (check! x-double?)
      ;data =fn=> (check! failing-checker)
      ;data =fn=> (check! (embeds?* {:a "A"}) (embeds?* {:b {:c "C"}}))
      )))
