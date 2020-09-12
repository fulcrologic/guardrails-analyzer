(ns com.fulcrologic.guardrails-pro.analysis.analyzer-spec
  (:require
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [fulcro-spec.core :refer [specification component assertions when-mocking! =fn=>]]
    [clojure.spec.alpha :as s]))

(grp/>defn test_int->int [x]
  [int? => int?]
  (inc x))

(defn with-mocked-errors [cb]
  (let [errors (atom [])]
    (when-mocking!
      (grp.art/record-error! _ error) => (swap! errors conj error)
      (cb errors))))

(defn with-mocked-warnings [cb]
  (let [warnings (atom [])]
    (when-mocking!
      (grp.art/record-warning! _ warning) => (swap! warnings conj warning)
      (cb warnings))))

(specification "analyze-let-like-form!" :integration
  (component "A simple let"
    (with-mocked-errors
      (fn [errors]
        (let [env (grp.art/build-env)]
          (grp.ana/analyze! env `(let [a# :a-kw] (test_int->int a#)))
          (assertions
            "It finds an error"
            (count @errors) => 1))))))

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
