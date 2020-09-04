(ns com.fulcrologic.guardrails-pro.ftags.clojure-core
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [=> | <- ?]]
    [com.fulcrologic.guardrails-pro.core :refer [>ftag >defn]]))

(>ftag ^:pure? clojure.core/str
  ([] [=> string? <- :pure])
  ([x] [any? => string?])
  ([x & ys] [any? (s/* any?) => string?]))

(>defn test:str [x]
  [int? => keyword?]
  (str "x = " x))

(>ftag ^:pure? clojure.core/get
  ([map key] [map? any? => any?])
  ([map key not-found] [map? any? any? => any?]))

(>defn test:get [x]
  [int? => keyword?]
  (get {:a x} :a))

(>ftag ^:pure? clojure.core/+
  ([] [=> number?])
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/coll-of number?) => number?]))

(>defn test:+ [x]
  [int? => keyword?]
  (+ x 1000))

(>ftag ^:pure? clojure.core/-
  ([] [=> number?])
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/coll-of number?) => number?]))

(>ftag ^:pure? clojure.core/*
  ([] [=> number?])
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/coll-of number?) => number?]))

(>ftag ^:pure? clojure.core//
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/coll-of number?) => number?]))

(>ftag ^:pure? clojure.core/=
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? clojure.core/==
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? clojure.core/<
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? clojure.core/<=
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? clojure.core/>
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? clojure.core/>=
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? clojure.core/assoc
  ([coll k v] [map? any? any? => map?])
  ([coll k v & kvs] [map? any? any? (s/* any?) | #(even? (count kvs)) => map?]))

;; TASK: pure can fail so try catch
(>defn test:assoc [m]
  [map? => keyword?]
  (assoc m :k :v))

(>ftag ^:pure? clojure.core/assoc-in
  [m [k & ks] v] [map? (s/+ any?) any? => map?])

(>ftag ^:pure? clojure.core/butlast
  [coll] [coll? => coll?])

(>ftag ^:pure? clojure.core/coll?
  [x] [coll? => boolean?])

(>ftag ^:pure? clojure.core/concat
  ([] [=> seq?])
  ([x] [coll? => seq?])
  ([x y] [coll? coll? => seq?])
  ([x y & zs] [coll? coll? (s/+ coll?) => seq?]))

(>ftag ^:pure? clojure.core/conj
  ([coll x] [coll? any? => coll?])
  ([coll x & xs] [coll? any? (s/+ any?) => coll?]))

(>ftag ^:pure? clojure.core/cons
  [x seq] [any? coll? => seq?])

(>ftag ^:pure? clojure.core/contains?
  [coll key] [coll? any? => boolean?])

(>ftag ^:pure? clojure.core/count
  [coll] [coll? => pos-int?])

(>ftag ^:pure? clojure.core/dec
  [x] [number? => number?])

(>ftag ^:pure? clojure.core/dissoc
  ([map] [map? => map?])
  ([map key] [map? any? => map?])
  ([map key & ks] [map? any? (s/+ any?) => map?]))

(>ftag ^:pure? clojure.core/drop
  ;;TODO: >fspec + transducers
  ([n coll] [number? coll? => coll?]))

(>ftag ^:pure? clojure.core/drop-last
  ([coll] [coll? => coll?])
  ([n coll] [number? coll? => coll?]))

;; TODO: HOFs
#_(>ftag ^:pure? clojure.core/every?)

;; TODO: objects
#_(>ftag ^:pure? clojure.core/ex-info)

(>ftag ^:pure? clojure.core/first
  [coll] [coll? => any?])

(>ftag ^:pure? clojure.core/get-in
  ([m ks] [map? (s/coll-of any? :kind vector?) => any?])
  ([m ks not-found] [map? (s/coll-of any? :kind vector?) any? => any?]))

;; TODO: HOFs
#_(>ftag ^:pure? clojure.core/group-by)

(>ftag ^:pure? clojure.core/hash
  [x] [any? => int?])

(>ftag ^:pure? clojure.core/inc
  [x] [number? => number?])

(>ftag ^:pure? clojure.core/inst-ms
  [inst] [inst? => int?])

;;TODO: >fspec + transducers
#_(>ftag ^:pure? clojure.core/into)

(>ftag ^:pure? clojure.core/keys
  [map] [map? => (s/coll-of any? :kind seq?)])

(>ftag ^:pure? clojure.core/keyword
  ([name] [string? => keyword?])
  ([ns name] [string? string? => keyword?]))

(>ftag ^:pure? clojure.core/key
  [e] [map-entry? => any?])

(>ftag ^:pure? clojure.core/last
  [coll] [coll? => any?])

(>ftag ^:pure? clojure.core/list
  [& items] [(s/* any?) => list?])

(>ftag ^:pure? clojure.core/max
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/+ number?) => number?]))

(>ftag ^:pure? clojure.core/merge
  [& maps] [(s/+ map?) => map?])

;; TODO: HOFs
#_(>ftag ^:pure? clojure.core/merge-with)

(>ftag ^:pure? clojure.core/meta
  [obj] [any? => (? map?)])

(>ftag ^:pure? clojure.core/min
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/+ number?) => number?]))

(s/def ::named
  (s/or
    :string string?
    :keyword keyword?
    :symbol symbol?))

(>ftag ^:pure? clojure.core/name
  [x] [::named => string?])

(>ftag ^:pure? clojure.core/namespace
  [x] [::named => (? string?)])

(>ftag ^:pure? clojure.core/next
  [coll] [coll? => (? coll?)])

(>ftag ^:pure? clojure.core/not
  [x] [any? => boolean?])

(>ftag ^:pure? clojure.core/not=
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/coll-of any?) => boolean?]))

(>ftag ^:pure? clojure.core/nth
  ([coll index] [coll? number? => any?])
  ([coll index not-found] [coll? number? any? => any?]))

(>ftag ^:pure? clojure.core/qualified-ident?
  [x] [any? => boolean?])

(>ftag ^:pure? clojure.core/qualified-symbol?
  [x] [any? => boolean?])

(>ftag ^:pure? clojure.core/qualified-keyword?
  [x] [any? => boolean?])

(>ftag ^:pure? clojure.core/quot
  [num div] [number? number? => number?])

(>ftag ^:pure? clojure.core/rand
  ([] [=> number?])
  ([n] [number? => number?]))

(>ftag ^:pure? clojure.core/rand-int
  [n] [int? => int?])

(>ftag ^:pure? clojure.core/rand-nth
  [coll] [coll? => any?])

(>ftag ^:pure? clojure.core/range
  ([] [=> seq?])
  ([end] [number? => seq?])
  ([start end] [number? number? => seq?])
  ([start end step] [number? number? number? => seq?]))

;; TODO: objects
#_(>ftag ^:pure? clojure.core/re-find)

;; TODO: objects
#_(>ftag ^:pure? clojure.core/re-seq)

(>ftag ^:pure? clojure.core/rest
  [coll] [coll? => (? seq?)])

(>ftag ^:pure? clojure.core/reverse
  [coll] [coll? => seq?])

(>ftag ^:pure? clojure.core/second
  [x] [coll? => any?])

(>ftag ^:pure? clojure.core/select-keys
  [map keyseq] [map? sequential? => map?])

;;TODO: interface Iterable / cljs ???
(>ftag ^:pure? clojure.core/seq
  [coll] [coll? => (? coll?)])

(>ftag ^:pure? clojure.core/seq?
  [x] [any? => boolean?])

(>ftag ^:pure? clojure.core/set
  [coll] [coll? => set?])

(>ftag ^:pure? clojure.core/some?
  [x] [any? => boolean?])

;;TODO: HOFs / interface Comparator
#_(>ftag ^:pure? clojure.core/sort cljs.core/sort)

;;TODO: HOFs / interface Comparator
#_(>ftag ^:pure? clojure.core/sort-by)

(>ftag ^:pure? clojure.core/symbol
  ([name] [::named => symbol?])
  ([ns name] [string? string? => symbol?]))

(>ftag ^:pure? clojure.core/take
  ;;TODO: >fspec + transducers
  ([n coll] [number? coll? => coll?]))

(>ftag ^:pure? clojure.core/take-last
  [n coll] [number? coll? => coll?])

(>ftag ^:pure? clojure.core/take-nth
  [n coll] [number? coll? => coll?])

;; TODO: objects
#_(>ftag ^:pure? clojure.core/type)

(>ftag ^:pure? clojure.core/val
  [e] [map-entry? => any?])

(>ftag ^:pure? clojure.core/vals
  [map] [map? => (s/coll-of any? :kind seq?)])

(>ftag ^:pure? clojure.core/vec
  [coll] [coll? => vector?])

(>ftag ^:pure? clojure.core/vector
  [& xs] [(s/* any?) => vector?])

(>ftag ^:pure? clojure.core/zipmap
  [keys vals] [coll? coll? => map?])
