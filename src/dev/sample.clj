(ns
  ^{:guardrails/spec-system :my-spec-system}
  sample
  (:require
    [com.fulcrologic.copilot.analysis.fdefs.clojure-core]
    [com.fulcrologic.guardrails.core :refer [>defn => | ?]]
    [com.fulcrologic.guardrails.impl.externs :as externs]
    [com.fulcrologic.guardrails.registry :as reg]
    [clojure.spec.alpha :as s]))

(s/def :person/first-name string?)
(s/def :person/last-name string?)
(s/def :person/age int?)

(>defn full-name [{:person/keys [first-name last-name] :as person}]
  ^:pure [(s/keys :req [:person/first-name :person/last-name])
          => (s/keys :req [:person/first-name :person/last-name
                           :person/full-name])]
  (assoc person :person/full-name (str first-name " " last-name)))

(>defn person? [v]
  ^:pure [any? => boolean?]
  (and
    (map? v)
    (contains? v :person/id)))

(>defn new-person
  ([first-name]
   ^:pure? [string? => (s/keys :req [:person/first-name
                                     :person/last-name
                                     :person/full-name
                                     :person/age])]
   (let [person  {:person/first-name first-name
                  :person/last-name  "Kay"
                  :person/age        44}
         person2 (full-name person)
         v       (if (person? person2)
                   1
                   "hello")]
     v))
  ([]
   ^:pure [=> (s/keys :req [:person/first-name
                            :person/last-name
                            :person/full-name
                            :person/age])]
   (let [person  {:person/first-name "Tony"
                  :person/last-name  "Kay"
                  :person/age        44}
         person2 (full-name person)
         v       (if (person? person2)
                   1
                   "hello")]
     v)))

(comment
  (externs/function-info 'clojure.core/print)
  (externs/spec-system 'clojure.core/print)
  (externs/pure? 'clojure.core/namespace 1))
