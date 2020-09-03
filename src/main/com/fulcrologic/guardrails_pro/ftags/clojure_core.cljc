(ns com.fulcrologic.guardrails-pro.ftags.clojure-core
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [=> | <-]]
    [com.fulcrologic.guardrails-pro.core :refer [>ftag >defn]]))

;; TASK: Use mm for type propagation. Layer above does error handling via env
(defmulti rv-generator (fn [k original-f return-spec & a-sample-of-args] k))
(defmethod rv-generator :pure (fn [_ f return-spec & args] (apply f args)))
(defmethod rv-generator :merge-arg1 (fn [_ _ return-sample & args] (let [arg1 (first args)] (merge return-sample arg1))))
(defmethod rv-generator :map-like (fn [_ _ return-sample & args] (let [arg1 (first args)] (merge return-sample arg1))))

;; HOF notes
;(>defn f [m]
;  (let [a (range 1 2)                                       ;; (s/coll-of int?)
;        (>fn [a] [int? => string?]) (comp
;                                      (>fn [a] [map? => string?])
;                                      (>fn [a] [(>fspec [n] [int? => int?]) => map?])
;                                      some-f
;                                      #_(>fn [a] [int? => (>fspec [x] [number? => number?] string?)]))
;        bb (into #{}
;             (comp
;               (map f) ;; >fspec ...
;               (filter :person/fat?))
;             people)
;        new-seq (map (>fn [x] ^:boo [int? => int?]
;                       (map (fn ...) ...)
;                       m) a)]))

(>defn add-last-name [p]
  ^::grp/merge-arg2 [db (s/keys :req [id]) | #(...) => (s/keys :req [full-name]) | ...]
  [db person]
  (let [person (db/lookup id)]
    (assoc person ...)))

(>ftag ^:pure? #?(:cljs cljs.core/str :clj clojure.core/str)
  ([] [=> string? <- :pure])
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
