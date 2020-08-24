(ns com.fulcrologic.guardrails-pro.utils-spec
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.utils :as grp.u]
    [fulcro-spec.core :refer [specification assertions component => =fn=>]]))

(s/def :NS/foo keyword?)
(s/def ::foo int?)
(s/def ::bar string?)

(specification "destructure*"
  (let [test-env (grp.art/build-env)
        test-td {::grp.art/type "test type desc"}]
    (assertions
      "simple symbol"
      (grp.u/destructure* test-env 'foo test-td)
      => {'foo test-td})
    (component "vector destructuring"
      (assertions
        "returns the symbol used to bind to the entire collection"
        (grp.u/destructure* test-env '[foo bar :as coll] test-td)
        => {'coll test-td}
        "ignores all other symbols"
        (grp.u/destructure* test-env '[foo bar] test-td)
        => {}))
    (component "map destructuring"
      (assertions
        "simple keyword"
        (-> (grp.u/destructure* test-env '{foo ::foo} test-td)
          (get-in ['foo ::grp.art/spec]))
        => (s/get-spec ::foo)
        (-> (grp.u/destructure* test-env '{foo ::foo} test-td)
          (get-in ['foo ::grp.art/samples]))
        =fn=> #(every? int? %)
        "if the keyword does not have a spec it returns no entry for it"
        (grp.u/destructure* test-env '{foo :ERROR} test-td)
        => {})
      (component ":as binding"
        (assertions
          (grp.u/destructure* test-env '{:as foo} test-td)
          => {'foo test-td}
          (grp.u/destructure* test-env '{:ERROR/as foo} test-td)
          => {}))
      (component "keys destructuring"
        (assertions
          "not namespaced keywords are ignored"
          (grp.u/destructure* test-env '{:keys [foo]} test-td)
          => {}
          "can lookup specs by namespace"
          (-> (grp.u/destructure* test-env '{:NS/keys [foo]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => (s/get-spec :NS/foo)
          (-> (grp.u/destructure* test-env '{:NS/keys [foo]} test-td)
            (get-in ['foo ::grp.art/samples]))
          =fn=> #(every? keyword? %)
          (-> (grp.u/destructure* test-env '{::keys [foo]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => (s/get-spec ::foo)
          (-> (grp.u/destructure* test-env '{::keys [foo]} test-td)
            (get-in ['foo ::grp.art/samples]))
          =fn=> #(every? int? %)
          (-> (grp.u/destructure* test-env '{::keys [foo bar]} test-td)
            (get-in ['foo ::grp.art/spec]))
          => (s/get-spec ::foo)
          (-> (grp.u/destructure* test-env '{::keys [foo bar]} test-td)
            (get-in ['bar ::grp.art/spec]))
          => (s/get-spec ::bar)
          "ignores symbol if it has no spec"
          (grp.u/destructure* test-env '{:FAKE/keys [foo]} test-td)
          => {})))))
