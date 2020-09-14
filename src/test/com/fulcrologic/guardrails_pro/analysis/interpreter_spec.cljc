(ns com.fulcrologic.guardrails-pro.analysis.interpreter-spec
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.core :refer [>defn >fn]]
    [com.fulcrologic.guardrails-pro.analysis.interpreter :refer [check!]]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [fulcro-spec.core :refer [specification assertions]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(>defn ^:pure greet [x]
  [string? => string?]
  (str "hello: <" x ">"))

(>defn ^:pure test:map:>fn [arg]
  [int? => (s/coll-of int?)]
  (let [const 100
        random (rand-int const)
        maaap (map (>fn ^:pure foo [i] [int? => int?]
                     (+ i const random arg))
                (range 7))]
    maaap))
#_(test:map:>fn 10000)

(>defn test:nested-fns [arg]
  [int? => (s/coll-of int?)]
  (let [const 300]
    (map (>fn ^:pure FOO [i] [int? => int?]
           (let [c 23]
             (map (>fn ^:pure BAR [j] [int? => int?]
                    (+ j c i const arg))
               [1 2 3 4 5])))
      (range 1000 1005 1))))
#_(test:nested-fns 10000)

(defn with-mocked-errors [sym]
  (let [errors (atom [])]
    (with-redefs
      [grp.art/record-error! (fn [_ error] (swap! errors conj error))]
      (check! sym))
    @errors))

;; NOTE: dont check error message, will change
;; TODO: add & use :error/type (machine readable)
(specification "Checking a function"
  (let [errors (with-mocked-errors `test:nested-fns)]
    (assertions
      errors => [])))
