(ns com.fulcrologic.guardrails-pro.ftags.clojure-core
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [=> |]]
    [com.fulcrologic.guardrails-pro.core :refer [>ftag >defn]]))

(>ftag ^:pure? #?(:cljs cljs.core/str :clj clojure.core/str)
  ([] [=> string?])
  ([x] [any? => string?])
  ([x & ys] [any? (s/* any?) => string?]))

(>defn test:str [x]
  [int? => keyword?]
  (str "n = " x))

(>ftag ^:pure? #?(:cljs cljs.core/get :clj clojure.core/get)
  ([map key] [map? any? => any?])
  ([map key not-found] [map? any? any? => any?]))

(>defn test:get [x]
  [int? => keyword?]
  (get {:a x} :a))

(>ftag ^:pure? #?(:cljs cljs.core/+ :clj clojure.core/+)
  ([] [=> number?])
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/coll-of number?) => number?]))

(>defn test:+ [x]
  [int? => keyword?]
  (+ x 1000))

(>ftag ^:pure? #?(:cljs cljs.core/- :clj clojure.core/-)
  ([] [=> number?])
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/coll-of number?) => number?]))

(>ftag ^:pure? #?(:cljs cljs.core/* :clj clojure.core/*)
  ([] [=> number?])
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/coll-of number?) => number?]))

(>ftag ^:pure? #?(:cljs cljs.core// :clj clojure.core//)
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/coll-of number?) => number?]))

(>ftag ^:pure? #?(:cljs cljs.core/= :clj clojure.core/=)
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? #?(:cljs cljs.core/== :clj clojure.core/==)
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? #?(:cljs cljs.core/< :clj clojure.core/<)
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? #?(:cljs cljs.core/<= :clj clojure.core/<=)
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? #?(:cljs cljs.core/> :clj clojure.core/>)
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? #?(:cljs cljs.core/>= :clj clojure.core/>=)
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? #?(:cljs cljs.core/assoc :clj clojure.core/assoc)
  ([map key val] [map? any? any? => map?])
  ([map key val & kvs] [map? any? any? (s/* any?) | #(even? (count kvs)) => map?]))

#_(>defn test:assoc [m]
  [int? => map?]
  (assoc m :k :v))

(comment
  assoc
  assoc-in
  butlast
  case
  coll?
  concat
  cond
  condp
  conj
  cons
  contains?
  count
  dec
  dissoc
  doseq
  drop
  drop-last
  every?
  ex-info
  first
  for
  get-in
  group-by
  hash
  if
  if-let
  if-not
  inc
  inst-ms
  into
  keys
  keyword
  key
  last
  letfn
  list
  loop
  recur
  max
  merge
  merge-with
  meta
  min
  name
  namespace
  next
  not
  not=
  nth
  or
  qualified-ident?
  qualified-symbol?
  qualified-keyword?
  quot
  rand
  rand-int
  rand-nth
  range
  re-find
  re-seq
  rest
  reverse
  second
  select-keys
  seq
  seq?
  set
  some?
  sort
  sort-by
  symbol
  take
  take-last
  take-nth
  try
  throw
  type
  val
  vals
  vec
  vector
  when
  when-let
  when-not
  zipmap
  )
