(ns self-checker
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [com.fulcrologic.guardrails-pro.ui.reporter :as reporter]
    [cljs.spec.alpha :as s]))

(defn nestring [& samples]
  (s/with-gen (s/and string? seq) #(s/gen (set samples))))

(s/def ::non-empty-string (nestring "a" "a medium string" "a very long string that has a lot in it"))
(s/def :person/id pos-int?)
(s/def :person/fname (nestring "Sam" "Sally" "Amanda" "Bob" "Abed" "Lynn" "Leslie"))
(s/def :person/lname (nestring "Smith" "Wang" "Gopinath" "Mason"))
(s/def :person/full-name (nestring "Sam Smith" "Amanda Wang"))
(s/def ::person (s/keys :req [:person/id :person/fname :person/lname]
                  :opt [:person/full-name]))

(>defn new-person
  [id fn ln]
  ^:pure [pos-int? ::non-empty-string ::non-empty-string => ::person]
  (let [p  {:person/id    id
            :person/lname ln}
        p2 (assoc p :person/fname fn)]
    p2))

(>defn add-even [a b]
  [int? int? :st #(and (even? a) (even? b)) => int?]
  (str a b "c"))

(>defn with-full-name [person]
  ^:pure [(s/keys :req [:person/fname :person/lname])
          => (s/keys :req [:person/full-name])]
  (assoc person :person/full-name
    (str (get person :person/fname) " " (get person :person/lname))))

(>defn test-person [input-id]
  ^:pure [pos-int? => int?]
  (let [p (new-person input-id "Tom" "Bob")
        b (with-full-name p)
        d (get b :person/id)]
    d))

(defn init []
  (reporter/start! true))

(defn refresh []
  (reporter/hot-reload!))
