(ns sample-malli
  (:require
    [com.fulcrologic.guardrails.impl.externs :as externs]
    [com.fulcrologic.guardrails.malli.core :refer [=> >def >defn]]))

;; TASK: Malli generators/specs not used for sampler yet...

(>def :person/id :int)
(>def :person/first-name :string)
(>def :person/last-name :string)
(>def :person/full-name :string)
(>def :person/age :int)

(>defn full-name [{:person/keys [first-name last-name] :as person}]
  ^:pure [[:map :person/first-name :person/last-name]
          => [:map :person/first-name :person/last-name
              :person/full-name]]
  (assoc person :person/full-name (str first-name " " last-name)))

(>defn person? [v]
  ^:pure [:any => :boolean]
  (contains? v :person/id))

(>defn new-person
  ([first-name]
   ^:pure? [:string => [:map :person/first-name :person/last-name :person/full-name :person/age]]
   (let [person  {:person/id         1
                  :person/first-name first-name
                  :person/last-name  "Kay"
                  :person/age        44}
         person2 (full-name person)]
     person2))
  ([]
   ^:pure [=> [:map :person/first-name :person/last-name :person/full-name :person/age]]
   (let [person  {:person/id         1
                  :person/first-name "Tony"
                  :person/last-name  "Kay"
                  :person/age        44}
         person2 (full-name person)]
     person2)))


(comment
  (externs/function-info `new-person)
  (externs/spec-system 'clojure.core/print)
  (externs/pure? 'clojure.core/namespace 1)
  (externs/run-registry-function `new-person ["bob"])

  )
