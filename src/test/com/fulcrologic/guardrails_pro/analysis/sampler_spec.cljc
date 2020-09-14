(ns com.fulcrologic.guardrails-pro.analysis.sampler-spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators :as tc.gen]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [fulcro-spec.core :refer [specification component assertions when-mocking! when-mocking provided]]))

(defn with-mocked-errors [cb]
  (let [errors (atom [])]
    (when-mocking!
      (grp.art/record-error! _ error) => (swap! errors conj error)
      (cb errors))))

(specification "try-sampling!"
  (let [env (grp.art/build-env)]
    (with-mocked-errors
      (fn [errors]
        (grp.sampler/try-sampling! env
          (tc.gen/fmap #(assoc % :k :v) (s/gen int?))
          {::grp.art/original-expression :TEST})
        (assertions
          @errors => [#::grp.art{:message "Failed to generate samples!"
                                 :original-expression :TEST}])))))

(specification "convert-shorthand-metadata"
  (assertions
    "rewrites simple keywords"
    (grp.sampler/convert-shorthand-metadata {:pure? 0})
    => {::grp.sampler/pure 0}
    (grp.sampler/convert-shorthand-metadata {:pure 1})
    => {::grp.sampler/pure 1}
    (grp.sampler/convert-shorthand-metadata {:merge-arg 2})
    => {::grp.sampler/merge-arg 2}
    "passes through unknown metadata"
    (grp.sampler/convert-shorthand-metadata {:stuff "things"})
    => {:stuff "things"})
  (component "if the incoming map contains ::grp.sampler"
    (assertions
      "it rewrites a simple keyword"
      (grp.sampler/convert-shorthand-metadata {::grp.sampler/sampler :pure})
      => {::grp.sampler/sampler ::grp.sampler/pure}
      "it rewrites a vector's first element"
      (grp.sampler/convert-shorthand-metadata {::grp.sampler/sampler [:merge-arg 3]})
      => {::grp.sampler/sampler [::grp.sampler/merge-arg 3]}
      "still rewrites all other keywords in the map"
      (grp.sampler/convert-shorthand-metadata {::grp.sampler/sampler :pure :pure 4 :foo :bar})
      => {::grp.sampler/sampler ::grp.sampler/pure
          ::grp.sampler/pure    4
          :foo                  :bar})))

(specification "derive-sampler-type"
  (assertions
    "returns the first valid sampler if there are multiple"
    (grp.sampler/derive-sampler-type
      {::grp.sampler/pure true
       ::grp.sampler/merge-arg true})
    => ::grp.sampler/pure
    "returns nil if there are no valid samplers"
    (grp.sampler/derive-sampler-type
      {:foobar true})
    => nil
    "returns whatever is at ::grp.sampler/sampler"
    (grp.sampler/derive-sampler-type
      {::grp.sampler/sampler :pure})
    => :pure
    (grp.sampler/derive-sampler-type
      {::grp.sampler/sampler ::grp.sampler/pure})
    => ::grp.sampler/pure
    (grp.sampler/derive-sampler-type
      {::grp.sampler/sampler [:foobar 1]})
    => [:foobar 1]))

(specification "return-sample-generator"
  (let [env (grp.art/build-env)]
    (assertions
      (grp.sampler/return-sample-generator env nil
        {:return-sample ::test-sample})
      => ::test-sample
      (grp.sampler/return-sample-generator env ::grp.sampler/pure
        {:fn-ref str :args ["pure" \: "test"]})
      => "pure:test"
      (grp.sampler/return-sample-generator env [::grp.sampler/merge-arg 1]
        {:args [:db {:person/name "john"}]
         :params 1
         :return-sample {:person/full-name "john doe"}})
      => #:person{:name "john" :full-name "john doe"})
    (component "map-like"
      (when-mocking
        (grp.sampler/get-gspec _ _) => nil
        (assertions
          "returns :return-sample if no sampler"
          (grp.sampler/return-sample-generator env ::grp.sampler/map-like
            {:return-sample ::STUB_RETURN_SAMPLE
             :argtypes [{}]})
          => ::STUB_RETURN_SAMPLE))
      (provided "calls sample! on function if sampler metadata"
        (grp.sampler/make-generator _env _fn argtypes)
        => (do (assertions
                 (map ::grp.art/samples argtypes)
                 => [#{1 2 3} #{4 5 6 7 8 9}])
             ::MOCK_GENERATOR)
        (tc.gen/generate _) => ::MOCK_SAMPLES
        (grp.sampler/get-gspec _ _) => {::grp.art/sampler ::grp.sampler/pure}
        (assertions
          (grp.sampler/return-sample-generator env ::grp.sampler/map-like
            {:argtypes [{:STUB :FUNCTION}
                        {::grp.art/samples #{[1 2 3]}}
                        {::grp.art/samples #{[4 5 6] [7 8 9]}}]})
          => ::MOCK_SAMPLES)))))
