(ns
  ^{:guardrails/spec-system :my-spec-system}
  sample
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.copilot.analysis.fdefs.clojure-core]
    [com.fulcrologic.guardrails.core :refer [=> >defn]]
    [com.fulcrologic.guardrails.impl.externs :as externs]))

(s/def :person/id int?)
(s/def :person/first-name string?)
(s/def :person/last-name string?)
(s/def :person/full-name string?)
(s/def :person/age int?)

(>defn full-name [{:person/keys [first-name last-name] :as person}]
  ^:pure [(s/keys :req [:person/first-name :person/last-name])
          => (s/keys :req [:person/first-name :person/last-name
                           :person/full-name])]
  (assoc person :person/full-name (str first-name " " last-name)))

(>defn person? [v]
  ^:pure [any? => boolean?]
  (contains? v :person/id))

(>defn new-person
  ([first-name]
   ^:pure? [string? => (s/keys :req [:person/first-name
                                     :person/last-name
                                     :person/full-name
                                     :person/age])]
   (let [person  {:person/id         1
                  :person/first-name first-name
                  :person/last-name  "Kay"
                  :person/age        44}
         person2 (if (> 0.5 (rand)) (full-name person) person)]
     person2))
  ([]
   ^:pure [=> (s/keys :req [:person/first-name
                            :person/last-name
                            :person/full-name
                            :person/age])]
   (let [person  {:person/id         1
                  :person/first-name "Tony"
                  :person/last-name  "Kay"
                  :person/age        44}
         person2 (full-name person)
         v       (if (person? person2)
                   person2
                   :error)]
     v)))


(comment
  (externs/function-info `new-person)
  (externs/spec-system 'clojure.core/print)
  (externs/pure? 'clojure.core/namespace 1)
  (externs/run-registry-function `new-person ["bob"])

  )
