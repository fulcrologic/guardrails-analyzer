(ns com.fulcrologic.guardrails-pro.analysis.analyzer.functions-spec
  (:require
    com.fulcrologic.guardrails-pro.ftags.clojure-core ;; NOTE: required
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.functions :as grp.ana.fn]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.test-fixtures :as tf]
    [com.fulcrologic.guardrails-pro.test-checkers :as tc]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn =>]]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions ]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(s/def ::num number?)
(s/def ::str string?)
(s/def ::kw keyword?)
(s/def ::map (s/keys :req [::kw ::str] :opt [::map]))

(>defn MAP [_] [any? => ::map] nil)

(specification "analyze:get-in" :integration
  (let [env (tf/test-env)]
    (assertions
      "if the last entry is not spec'ed, uses pure get-in"
      (grp.ana/analyze! env
        `(get-in {:a 1} [:a]))
      =check=> (_/embeds?* {::grp.art/samples #{1}})
      (grp.ana/analyze! env
        `(get-in {:a [2]} [:a 0]))
      =check=> (_/embeds?* {::grp.art/samples #{2}})
      "if the last path entry is spec'ed, uses its generator"
      (grp.ana/analyze! env
        `(get-in (MAP 3) [::str]))
      =check=> (_/embeds?* {::grp.art/samples (_/every?* (_/is?* string?))})
      "checks that each path item is possible to get from the accumulated samples"
      (grp.ana/analyze! env
        `(get-in (MAP 5) [::num]))
      =check=> (_/embeds?* {::grp.art/samples (_/every?* (_/is?* number?))})
      (tf/capture-warnings grp.ana/analyze! env
        `(get-in (MAP 6) [::num]))
      =check=> (_/seq-matches?*
                 [(_/embeds?* #::grp.art{:problem-type :warning/get-in-might-never-succeed})])
      (tf/capture-warnings grp.ana/analyze! env
        `(get-in (MAP 7) [::map ::num]))
      =check=> (_/seq-matches?*
                 [(_/embeds?* #::grp.art{:problem-type :warning/get-in-might-never-succeed})]))))
