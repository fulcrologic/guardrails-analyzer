(ns com.fulcrologic.copilot.analysis.destructuring-spec
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.analysis.destructuring :as cp.destr]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [clojure.test]
    [fulcro-spec.core :refer [specification component assertions]]
    [fulcro-spec.check :as _]))

(s/def :NS/kw keyword?)
(s/def ::idx int?)
(s/def ::txt string?)

(specification "destructure!"
  (let [test-env (tf/test-env)
        test-td {::cp.art/type "test type desc"}]
    (assertions
      "simple symbol"
      (cp.destr/-destructure! test-env 'foo test-td)
      => {'foo (assoc test-td
                 ::cp.art/original-expression 'foo)})
    (component "vector destructuring"
      (let [coll-td {::cp.art/samples #{[1 2 3] [:a :b :c] '[x y z]}}]
        (assertions
          "binds the entire collection as the `:as sym` symbol"
          (cp.destr/-destructure! test-env '[:as coll] coll-td)
          => {'coll (assoc coll-td ::cp.art/original-expression '[:as coll])}
          "binds each symbol positionally"
          (cp.destr/-destructure! test-env '[one two] coll-td)
          =check=> (_/embeds?*
                     {'one {::cp.art/samples #{1 :a 'x}}
                      'two {::cp.art/samples #{2 :b 'y}}})
          "binds the rest of the collection as the `& sym` symbol"
          (cp.destr/-destructure! test-env '[one & rst] coll-td)
          =check=> (_/embeds?*
                     {'one {::cp.art/samples #{1 :a 'x}}
                      'rst {::cp.art/samples #{[2 3] [:b :c] '[y z]}}}))))
    (let [map-td {::cp.art/samples #{{::idx 123
                                       :NS/kw :abc}}}]
      (component "map destructuring"
        (assertions
          "simple keyword"
          (cp.destr/-destructure! test-env '{idx ::idx} map-td)
          =check=> (_/embeds?*
                     {'idx {::cp.art/spec ::idx
                            ::cp.art/samples (_/all*
                                                (_/is?* seq)
                                                (_/every?* (_/is?* int?)))}})
          "if the keyword does not have a spec it returns no entry for it"
          (cp.destr/-destructure! test-env '{err :ERROR} map-td)
          => {'err #::cp.art{:samples #{nil} :original-expression 'err}})
        (component ":as binding"
          (assertions
            (cp.destr/-destructure! test-env '{:as foo} map-td)
            => {'foo (assoc map-td ::cp.art/original-expression 'foo)}
            (cp.destr/-destructure! test-env '{:ERROR/as foo} map-td)
            => {}))
        (component "keys destructuring"
          (assertions
            "not namespaced keywords are ignored"
            (cp.destr/-destructure! test-env '{:keys [foo]} map-td)
            => {}
            "can lookup specs by namespace"
            (cp.destr/-destructure! test-env '{:NS/keys [kw]} map-td)
            =check=> (_/embeds?*
                       {'kw {::cp.art/spec :NS/kw
                             ::cp.art/samples (_/every?* (_/is?* keyword?))}})
            (cp.destr/-destructure! test-env '{::keys [idx]} map-td)
            =check=> (_/embeds?*
                       {'idx {::cp.art/spec ::idx
                              ::cp.art/samples (_/every?* (_/is?* int?))}})
            (cp.destr/-destructure! test-env '{::keys [idx txt]} map-td)
            =check=> (_/embeds?*
                       {'idx {::cp.art/spec ::idx}
                        'txt {::cp.art/spec ::txt}})
            "warns if qualified symbol has no spec"
            (tf/capture-warnings cp.destr/-destructure! test-env '{:FAKE/keys [foo]} map-td)
            =check=> (_/seq-matches?*
                       [(_/embeds?*
                          #::cp.art{:problem-type :warning/qualified-keyword-missing-spec
                                     :original-expression :FAKE/foo})])
            "errors if value for qualified keyword fails spec"
            (tf/capture-errors cp.destr/-destructure! test-env '{::keys [idx]}
              (update map-td ::cp.art/samples conj {::idx :ERROR}))
            =check=> (_/seq-matches?*
                       [(_/embeds?*
                          #::cp.art{:problem-type :error/value-failed-spec
                                     :original-expression 'idx})])))))))
