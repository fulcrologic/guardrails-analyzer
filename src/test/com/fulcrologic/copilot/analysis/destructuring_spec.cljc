(ns com.fulcrologic.copilot.analysis.destructuring-spec
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.copilot.artifacts :as grp.art]
    [com.fulcrologic.copilot.analysis.destructuring :as grp.destr]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [clojure.test]
    [fulcro-spec.core :refer [specification component assertions]]
    [fulcro-spec.check :as _]))

(s/def :NS/kw keyword?)
(s/def ::idx int?)
(s/def ::txt string?)

(specification "destructure!"
  (let [test-env (tf/test-env)
        test-td {::grp.art/type "test type desc"}]
    (assertions
      "simple symbol"
      (grp.destr/-destructure! test-env 'foo test-td)
      => {'foo (assoc test-td
                 ::grp.art/original-expression 'foo)})
    (component "vector destructuring"
      (let [coll-td {::grp.art/samples #{[1 2 3] [:a :b :c] '[x y z]}}]
        (assertions
          "binds the entire collection as the `:as sym` symbol"
          (grp.destr/-destructure! test-env '[:as coll] coll-td)
          => {'coll (assoc coll-td ::grp.art/original-expression '[:as coll])}
          "binds each symbol positionally"
          (grp.destr/-destructure! test-env '[one two] coll-td)
          =check=> (_/embeds?*
                     {'one {::grp.art/samples #{1 :a 'x}}
                      'two {::grp.art/samples #{2 :b 'y}}})
          "binds the rest of the collection as the `& sym` symbol"
          (grp.destr/-destructure! test-env '[one & rst] coll-td)
          =check=> (_/embeds?*
                     {'one {::grp.art/samples #{1 :a 'x}}
                      'rst {::grp.art/samples #{[2 3] [:b :c] '[y z]}}}))))
    (let [map-td {::grp.art/samples #{{::idx 123
                                       :NS/kw :abc}}}]
      (component "map destructuring"
        (assertions
          "simple keyword"
          (grp.destr/-destructure! test-env '{idx ::idx} map-td)
          =check=> (_/embeds?*
                     {'idx {::grp.art/spec ::idx
                            ::grp.art/samples (_/all*
                                                (_/is?* seq)
                                                (_/every?* (_/is?* int?)))}})
          "if the keyword does not have a spec it returns no entry for it"
          (grp.destr/-destructure! test-env '{err :ERROR} map-td)
          => {'err #::grp.art{:samples #{nil} :original-expression 'err}})
        (component ":as binding"
          (assertions
            (grp.destr/-destructure! test-env '{:as foo} map-td)
            => {'foo (assoc map-td ::grp.art/original-expression 'foo)}
            (grp.destr/-destructure! test-env '{:ERROR/as foo} map-td)
            => {}))
        (component "keys destructuring"
          (assertions
            "not namespaced keywords are ignored"
            (grp.destr/-destructure! test-env '{:keys [foo]} map-td)
            => {}
            "can lookup specs by namespace"
            (grp.destr/-destructure! test-env '{:NS/keys [kw]} map-td)
            =check=> (_/embeds?*
                       {'kw {::grp.art/spec :NS/kw
                             ::grp.art/samples (_/every?* (_/is?* keyword?))}})
            (grp.destr/-destructure! test-env '{::keys [idx]} map-td)
            =check=> (_/embeds?*
                       {'idx {::grp.art/spec ::idx
                              ::grp.art/samples (_/every?* (_/is?* int?))}})
            (grp.destr/-destructure! test-env '{::keys [idx txt]} map-td)
            =check=> (_/embeds?*
                       {'idx {::grp.art/spec ::idx}
                        'txt {::grp.art/spec ::txt}})
            "warns if qualified symbol has no spec"
            (tf/capture-warnings grp.destr/-destructure! test-env '{:FAKE/keys [foo]} map-td)
            =check=> (_/seq-matches?*
                       [(_/embeds?*
                          #::grp.art{:problem-type :warning/qualified-keyword-missing-spec
                                     :original-expression :FAKE/foo})])
            "errors if value for qualified keyword fails spec"
            (tf/capture-errors grp.destr/-destructure! test-env '{::keys [idx]}
              (update map-td ::grp.art/samples conj {::idx :ERROR}))
            =check=> (_/seq-matches?*
                       [(_/embeds?*
                          #::grp.art{:problem-type :error/value-failed-spec
                                     :original-expression 'idx})])))))))
