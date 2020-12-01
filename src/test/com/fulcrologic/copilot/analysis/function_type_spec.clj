(ns com.fulcrologic.copilot.analysis.function-type-spec
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.analysis.function-type :as cp.fnt]
    [fulcro-spec.core :refer [specification assertions]]
    [fulcro-spec.check :as _]))

(specification "interpret-gspec"
  (let [env (update (cp.art/build-env)
              ::cp.art/spec-registry merge
              `{int? :INT
                string? :STRING})]
    (assertions
      ;; TODO: generator
      (cp.fnt/interpret-gspec env '[x y]
        `[int? int? :st ~even? :ret string? :st ~odd?])
      =check=> (_/embeds?*
                 #::cp.art{:argument-specs      [:INT :INT]
                            :argument-types      ["clojure.core/int?" "clojure.core/int?"]
                            :argument-predicates [even?]
                            :return-spec         :STRING
                            :return-type         "clojure.core/string?"
                            :return-predicates   [odd?]})
      (cp.fnt/interpret-gspec env '[x y]
        `[int? :ret string?])
      =check=> (_/embeds?*
                 #::cp.art{:argument-specs      [:INT]
                            :argument-types      ["clojure.core/int?"]
                            :argument-predicates []
                            :return-spec         :STRING
                            :return-type         "clojure.core/string?"
                            :return-predicates   []}))))
