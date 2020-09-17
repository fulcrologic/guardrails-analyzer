(ns com.fulcrologic.guardrails-pro.analysis.interpreter-spec
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.core :refer [>defn >fn]]
    [com.fulcrologic.guardrails-pro.analysis.interpreter :refer [check!]]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [com.fulcrologic.guardrails.core :as gr :refer [=>]]
    [fulcro-spec.check :as _]
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

#_(specification "test:map:>fn"
  (assertions
    (tf/capture-errors check! `test:map:>fn)
    =check=> (_/all*
               (tf/of-length?* 1))))

(>defn ^:pure test:nested-fns [arg]
  [int? => (s/coll-of int?)]
  (let [const 300]
    (map (>fn ^:pure FOO [i] [int? => int?]
           (let [c 23]
             (map (>fn ^:pure BAR [j] [int? => int?]
                    (+ j c i const arg))
               [1 2 3 4 5])))
      (range 1000 1005 1))))
#_(test:nested-fns 10000)

#_(specification "test:nested-fns"
  (assertions
    (tf/capture-errors check! `test:nested-fns)
    =check=> (_/all*
               (tf/of-length?* 1))))

(>defn test:constantly [arg]
  [int? => int?]
  ((constantly :kw) 777))

#_(specification "test:constantly"
  (assertions
    (tf/capture-errors check! `test:constantly)
    =check=> (_/all*
               (tf/of-length?* 1)
               (_/seq-matches?*
                 [(_/embeds?*
                    {::grp.art/actual {::grp.art/failing-samples #{:kw}}
                     ;; FIXME: add & use error type
                     ::grp.art/message (_/re-find?* #"(?i)return value.*int\?")})]))))

(>defn test:map:constantly [arg]
  [int? => (s/coll-of int?)]
  (map (constantly :kw) [777]))

#_(specification "test:map:constantly"
  (assertions
    (tf/capture-errors check! `test:map:constantly)
    =check=> (_/all*
               (tf/of-length?* 1)
               (_/seq-matches?*
                 [(_/embeds?*
                    {::grp.art/actual {::grp.art/failing-samples #{[:kw]}}
                     ;; FIXME: add & use error type
                     ::grp.art/message (_/re-find?* #"(?i)return value.*coll-of.*int\?")})]))))
