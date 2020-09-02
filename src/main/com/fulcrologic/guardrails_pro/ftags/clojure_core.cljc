(ns com.fulcrologic.guardrails-pro.ftags.clojure-core
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :refer [>ftag >defn]]))

(>ftag ^:pure? #?(:cljs cljs.core/str :clj clojure.core/str)
  ([] [=> string?])
  ([x] [any? => string?])
  ([x & ys] [any? (s/coll-of any?) => string?]))

#_(>defn test:str [x]
  [int? => keyword?]
  (str "n = " x))

(>ftag ^:pure? #?(:cljs cljs.core/get :clj clojure.core/get)
  ([map key] [map? any? => any?])
  ([map key not-found] [map? any? any? => string?]))

#_(>defn test:get [x]
  [int? => keyword?]
  (get {:a x} :a))
