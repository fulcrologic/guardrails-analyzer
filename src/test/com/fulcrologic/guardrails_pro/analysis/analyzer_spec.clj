(ns com.fulcrologic.guardrails-pro.analysis.analyzer-spec
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [fulcro-spec.core :refer [specification component assertions when-mocking]]))

(defmethod grp.art/cljc-rewrite-sym-ns-mm "analyzer-spec" [sym] "cljc-analyzer-spec")

(specification "analyze-dispatch"
  (when-mocking
    (grp.art/function-detail _ sym) => (case sym 'foo true false)
    (grp.art/external-function-detail _ sym) => (case sym 'get true false)
    (grp.art/symbol-detail _ sym) => (case sym 'local true false)
    (methods _) => (zipmap '[bar c/a cljc-analyzer-spec/a] (repeat true))
    (let [env (grp.art/build-env)]
      (assertions
        (grp.ana/analyze-dispatch env '(foo :a)) => :function-call
        (grp.ana/analyze-dispatch env '(get :a)) => :external-function
        (grp.ana/analyze-dispatch env '(local))  => :symbol
        (grp.ana/analyze-dispatch env '(bar :a)) => 'bar
        (grp.ana/analyze-dispatch env '(c/a :a)) => 'c/a
        (grp.ana/analyze-dispatch env '(analyzer-spec/a :a)) => 'cljc-analyzer-spec/a
        (grp.ana/analyze-dispatch env '((q :b) :a)) => :function-expr
        (grp.ana/analyze-dispatch env '(a {})) => :ifn
        (grp.ana/analyze-dispatch env '({} :a)) => :ifn
        (grp.ana/analyze-dispatch env '(##NaN :a)) => :unknown))))

(grp/>defn test_int->int [x]
  [int? => int?]
  (inc x))

(specification "analyze-let-like-form!" :integration
  (component "A simple let"
    (assertions
      (tf/capture-errors grp.ana/analyze! (grp.art/build-env)
        `(let [a# :a-kw] (test_int->int a#)))
      =check=> (tf/of-length?* 1))))

(s/def ::number number?)
(s/def ::x ::number)
(s/def ::y ::number)
(s/def ::point (s/keys :req [::x ::y]))
(s/def ::color #{"red" "green" "blue"})
(s/def ::points (s/coll-of ::point :kind vector?))
(s/def ::polygon (s/keys :req [::points]
                   :opt [::color]))

(specification "Analyzing literal data structures" :integration
  (component "A non-nested literal map"
    (let [data   {:x 1
                  :y "hello"}
          actual (grp.ana/analyze-hashmap! (grp.art/build-env) data)]
      (assertions
        "Returns the exact literal nested hash map as the only single sample"
        (= (::grp.art/samples actual) #{data}) => true)))
  (component "A non-nested literal map with spec'd keys"
    (let [data   {::x 1
                  ::y "hello"}
          actual (grp.ana/analyze-hashmap! (grp.art/build-env) data)]
      (assertions
        "Returns the exact literal map as the only single sample"
        (= (::grp.art/samples actual) #{data}) => true)))
  (component "When given a nested hash map with all literal entries"
    (let [nested-data {::polygon {::color  "red"
                                  ::points [{::x 0 ::y 0}
                                            {::x 32.0 ::y 44}
                                            {::x 12.0 ::y 4}]}}
          actual      (grp.ana/analyze-hashmap! (grp.art/build-env) nested-data)]
      (assertions
        "Returns the exact literal nested hash map as the only single sample"
        (= (::grp.art/samples actual) #{nested-data}) => true))))
