(ns com.fulcrologic.guardrails-analyzer.generators-spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-analyzer.generators :as cp.gen]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [=> =check=> assertions specification]]))

(defprotocol FakeDB
  (query [this q]))

(specification "stub-impl-of"
  (assertions
    (cp.gen/stub-impl-of FakeDB)
    =check=> (_/is?* #(satisfies? FakeDB %))
    (query (cp.gen/stub-impl-of FakeDB) :q)
    => :query))

(specification "stub-spec"
  (assertions
    (s/valid? (cp.gen/stub-spec FakeDB)
      (reify FakeDB (query [& _])))
    => true
    (s/valid? (cp.gen/stub-spec FakeDB)
      :foo)
    => false
    (query (gen/generate (s/gen (cp.gen/stub-spec FakeDB))) :q)
    => :query))
