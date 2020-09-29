(ns com.fulcrologic.guardrails-pro.analysis.function-type-spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails.core :as gr :refer [=> | <-]]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [com.fulcrologic.guardrails-pro.test-checkers :as tc]
    [fulcro-spec.core :refer [specification behavior component assertions]]
    [fulcro-spec.check :as _]))

(s/def :NS/foo keyword?)
(s/def ::foo int?)
(s/def ::bar string?)

(specification "destructure!"
  (let [test-env (assoc (grp.art/build-env)
                   ::grp.art/checking-sym `grp.fnt/destructure!)
        test-td {::grp.art/type "test type desc"}]
    (assertions
      "simple symbol"
      (grp.fnt/destructure! test-env 'foo test-td)
      => {'foo (assoc test-td
                 ::grp.art/original-expression 'foo)})
    (component "vector destructuring"
      (assertions
        "returns the symbol used to bind to the entire collection"
        (grp.fnt/destructure! test-env '[foo bar :as coll] test-td)
        => {'coll (assoc test-td ::grp.art/original-expression '[foo bar :as coll])}
        "ignores all other symbols"
        (grp.fnt/destructure! test-env '[foo bar] test-td)
        => {}))
    (component "map destructuring"
      (assertions
        "simple keyword"
        (-> (grp.fnt/destructure! test-env '{foo ::foo} test-td)
          (get-in ['foo ::grp.art/spec]))
        => (s/get-spec ::foo)
        (-> (grp.fnt/destructure! test-env '{foo ::foo} test-td)
          (get-in ['foo ::grp.art/samples]))
        =check=> (_/every?* (_/is?* int?))
        "if the keyword does not have a spec it returns no entry for it"
        (grp.fnt/destructure! test-env '{foo :ERROR} test-td)
        => {})
      (component ":as binding"
        (assertions
          (grp.fnt/destructure! test-env '{:as foo} test-td)
          => {'foo (assoc test-td ::grp.art/original-expression 'foo)}
          (grp.fnt/destructure! test-env '{:ERROR/as foo} test-td)
          => {}))
      (component "keys destructuring"
        (assertions
          "not namespaced keywords are ignored"
          (grp.fnt/destructure! test-env '{:keys [foo]} test-td)
          => {}
          "can lookup specs by namespace"
          (-> (grp.fnt/destructure! test-env '{:NS/keys [foo]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => (s/get-spec :NS/foo)
          (-> (grp.fnt/destructure! test-env '{:NS/keys [foo]} test-td)
            (get-in ['foo ::grp.art/samples]))
          =check=> (_/every?* (_/is?* keyword?))
          (-> (grp.fnt/destructure! test-env '{::keys [foo]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => (s/get-spec ::foo)
          (-> (grp.fnt/destructure! test-env '{::keys [foo]} test-td)
            (get-in ['foo ::grp.art/samples]))
          =check=> (_/every?* (_/is?* int?))
          (-> (grp.fnt/destructure! test-env '{::keys [foo bar]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => (s/get-spec ::foo)
          (-> (grp.fnt/destructure! test-env '{::keys [foo bar]} test-td)
            (get-in ['bar ::grp.art/spec]))
          => (s/get-spec ::bar)
          "warns if qualified symbol has no spec"
          (tf/capture-warnings grp.fnt/destructure! test-env '{:FAKE/keys [foo]} test-td)
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
