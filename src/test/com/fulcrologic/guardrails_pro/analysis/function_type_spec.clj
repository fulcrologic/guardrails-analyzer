(ns com.fulcrologic.guardrails-pro.analysis.function-type-spec
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [fulcro-spec.core :refer [specification behavior component assertions]]
    [fulcro-spec.check :as _]))

(s/def :NS/foo keyword?)
(s/def ::foo int?)
(s/def ::bar string?)

(specification "destructure!"
  (let [test-env (assoc (grp.art/build-env)
                   ::grp.art/checking-sym `fake-test-sym)
        test-td {::grp.art/type "test type desc"}]
    (assertions
      "simple symbol"
      (grp.fnt/-destructure! test-env 'foo test-td)
      => {'foo (assoc test-td
                 ::grp.art/original-expression 'foo)})
    (component "vector destructuring"
      (let [coll-td {::grp.art/samples #{[1 2 3] [:a :b :c] '[x y z]}}]
        (assertions
          "binds the entire collection as the `:as sym` symbol"
          (grp.fnt/-destructure! test-env '[:as coll] coll-td)
          => {'coll (assoc coll-td ::grp.art/original-expression '[:as coll])}
          "binds each symbol positionally"
          (grp.fnt/-destructure! test-env '[one two] coll-td)
          =check=> (_/embeds?*
                     {'one {::grp.art/samples #{1 :a 'x}}
                      'two {::grp.art/samples #{2 :b 'y}}})
          "binds the rest of the collection as the `& sym` symbol"
          (grp.fnt/-destructure! test-env '[one & rst] coll-td)
          =check=> (_/embeds?*
                     {'one {::grp.art/samples #{1 :a 'x}}
                      'rst {::grp.art/samples #{[2 3] [:b :c] '[y z]}}}))))
    (component "map destructuring"
      (assertions
        "simple keyword"
        (grp.fnt/-destructure! test-env '{foo ::foo} test-td)
        =check=> (_/embeds?*
                   {'foo {::grp.art/spec ::foo
                          ::grp.art/samples (_/all*
                                              (_/is?* seq)
                                              (_/every?* (_/is?* int?)))}})
        "if the keyword does not have a spec it returns no entry for it"
        (grp.fnt/-destructure! test-env '{foo :ERROR} test-td)
        => {})
      (component ":as binding"
        (assertions
          (grp.fnt/-destructure! test-env '{:as foo} test-td)
          => {'foo (assoc test-td ::grp.art/original-expression 'foo)}
          (grp.fnt/-destructure! test-env '{:ERROR/as foo} test-td)
          => {}))
      (component "keys destructuring"
        (assertions
          "not namespaced keywords are ignored"
          (grp.fnt/-destructure! test-env '{:keys [foo]} test-td)
          => {}
          "can lookup specs by namespace"
          (-> (grp.fnt/-destructure! test-env '{:NS/keys [foo]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => :NS/foo
          (-> (grp.fnt/-destructure! test-env '{:NS/keys [foo]} test-td)
            (get-in ['foo ::grp.art/samples]))
          =check=> (_/every?* (_/is?* keyword?))
          (-> (grp.fnt/-destructure! test-env '{::keys [foo]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => ::foo
          (-> (grp.fnt/-destructure! test-env '{::keys [foo]} test-td)
            (get-in ['foo ::grp.art/samples]))
          =check=> (_/every?* (_/is?* int?))
          (-> (grp.fnt/-destructure! test-env '{::keys [foo bar]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => ::foo
          (-> (grp.fnt/-destructure! test-env '{::keys [foo bar]} test-td)
            (get-in ['bar ::grp.art/spec]))
          => ::bar
          "warns if qualified symbol has no spec"
          (tf/capture-warnings grp.fnt/-destructure! test-env '{:FAKE/keys [foo]} test-td)
          =check=> (_/seq-matches?*
                     [(_/embeds?*
                        #::grp.art{:problem-type :warning/qualified-keyword-missing-spec
                                   :original-expression 'foo})]))))))

(specification "interpret-gspec"
  (let [env (update (grp.art/build-env)
              ::grp.art/spec-registry merge
              `{int? :INT
                string? :STRING})]
    (assertions
      (grp.fnt/interpret-gspec env '[x y]
        `[int? int? :st ~even? :ret string? :st ~odd? :gen uuid])
      =check=> (_/embeds?*
                 #::grp.art{:argument-specs      [:INT :INT]
                            :argument-types      ["clojure.core/int?" "clojure.core/int?"]
                            :argument-predicates [even?]
                            :return-spec         :STRING
                            :return-type         "clojure.core/string?"
                            :return-predicates   [odd?]}))))
