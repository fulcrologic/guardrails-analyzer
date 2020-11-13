(ns com.fulcrologic.guardrails-pro.analysis.function-type-spec
  (:require
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [fulcro-spec.core :refer [specification assertions]]
    [fulcro-spec.check :as _]))

(specification "interpret-gspec"
  (let [env (update (grp.art/build-env)
              ::grp.art/spec-registry merge
              `{int? :INT
                string? :STRING})]
    (assertions
      ;; TODO: generator
      (grp.fnt/interpret-gspec env '[x y]
        `[int? int? :st ~even? :ret string? :st ~odd?])
      =check=> (_/embeds?*
                 #::grp.art{:argument-specs      [:INT :INT]
                            :argument-types      ["clojure.core/int?" "clojure.core/int?"]
                            :argument-predicates [even?]
                            :return-spec         :STRING
                            :return-type         "clojure.core/string?"
                            :return-predicates   [odd?]})
      (grp.fnt/interpret-gspec env '[x y]
        `[int? :ret string?])
      =check=> (_/embeds?*
                 #::grp.art{:argument-specs      [:INT]
                            :argument-types      ["clojure.core/int?"]
                            :argument-predicates []
                            :return-spec         :STRING
                            :return-type         "clojure.core/string?"
                            :return-predicates   []}))))
