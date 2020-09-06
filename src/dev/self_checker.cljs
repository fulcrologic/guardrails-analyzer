(ns self-checker
  (:require
    [com.fulcrologic.guardrails.core :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :refer [>defn]]
    [com.fulcrologic.guardrails-pro.runtime.reporter :as reporter]
    [com.fulcrologic.guardrails-pro.interpreter :as grp.intrp]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.ftags.clojure-core]
    [cljs.spec.gen.alpha :as gen]
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

(comment
  (gen/sample (s/gen map?)))

(>defn ^:pure? new-person
  [id fn ln]
  [int? string? string? => ::person]
  (let [p  {:person/id    id
            :person/lname ln}
        p2 (assoc p :person/fname fn)]
    p2))

(>defn ^:pure? with-full-name
  [person]
  [(s/keys :req [:person/fname :person/lname])
   => (s/keys :req [:person/full-name])]
  (assoc person :person/full-name (str (get person :person/fname) " " (get person :person/lname))))

(>defn ^:pure? test-person [p]
  [::person => int?]
  (let [p (new-person 9 "Tony" "Bob")
        b (with-full-name p)
        d (get b :person/id)]
    d))

(defn init []
  (reporter/start! true)
  (grp.intrp/check-all!))

(defn refresh []
  (reporter/hot-reload!)
  (grp.intrp/check-all!)
  (reporter/report-problems! @grp.art/problems))
