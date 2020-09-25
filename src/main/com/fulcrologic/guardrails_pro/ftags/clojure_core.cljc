(ns com.fulcrologic.guardrails-pro.ftags.clojure-core
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>fdef >fspec => | ?]]))

(>fdef ^:map-like clojure.core/map
  ([f coll & colls] [fn? sequential? (s/+ sequential?) => (s/coll-of any?)]))

(>fdef ^:pure clojure.core/partial
  [f & args] [fn? (s/+ any?) => fn?])

(>fdef ^:pure clojure.core/constantly
  [value] [any? => (>fspec [& args] ^:pure [(s/* any?) => any?])])

;; CONTEXT: future design work

;; NOTE: objects
#_(>fdef ^:pure clojure.core/ex-info)

;; NOTE: objects
#_(>fdef ^:pure clojure.core/re-find)

;; NOTE: objects
#_(>fdef ^:pure clojure.core/re-seq)

;; NOTE: interface Iterable / cljs ???
(>fdef ^:pure clojure.core/seq
  [coll] [coll? => (? coll?)])

;; NOTE: interface Comparator
#_(>fdef ^:pure clojure.core/sort)

;; NOTE: objects
#_(>fdef ^:pure clojure.core/type)

;; CONTEXT: Value Functions

(>fdef ^:pure clojure.core/+
  ([] [=> number?])
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/+ number?) => number?]))

(>fdef ^:pure clojure.core/-
  ([] [=> number?])
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/+ number?) => number?]))

(>fdef ^:pure clojure.core/*
  ([] [=> number?])
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/+ number?) => number?]))

(>fdef ^:pure clojure.core//
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/+ number?) => number?]))

(>fdef ^:pure clojure.core/=
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/+ any?) => boolean?]))

(>fdef ^:pure clojure.core/==
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/+ any?) => boolean?]))

(>fdef ^:pure clojure.core/<
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/+ any?) => boolean?]))

(>fdef ^:pure clojure.core/<=
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/+ any?) => boolean?]))

(>fdef ^:pure clojure.core/>
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/+ any?) => boolean?]))

(>fdef ^:pure clojure.core/>=
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/+ any?) => boolean?]))

(>fdef ^:pure clojure.core/assoc
  ([coll k v] [map? any? any? => map?])
  ([coll k v & kvs] [map? any? any? (s/* any?) | #(even? (count kvs)) => map?]))

(>fdef ^:pure clojure.core/assoc-in
  [m [k & ks] v] [map? (s/+ any?) any? => map?])

(>fdef ^:pure clojure.core/butlast
  [coll] [coll? => coll?])

(>fdef ^:pure clojure.core/coll?
  [x] [coll? => boolean?])

(>fdef ^:pure clojure.core/concat
  ([] [=> seq?])
  ([x] [coll? => seq?])
  ([x y] [coll? coll? => seq?])
  ([x y & zs] [coll? coll? (s/+ coll?) => seq?]))

(>fdef ^:pure clojure.core/conj
  ([coll x] [coll? any? => coll?])
  ([coll x & xs] [coll? any? (s/+ any?) => coll?]))

(>fdef ^:pure clojure.core/cons
  [x seq] [any? coll? => seq?])

(>fdef ^:pure clojure.core/contains?
  [coll key] [coll? any? => boolean?])

(>fdef ^:pure clojure.core/count
  [coll] [coll? => pos-int?])

(>fdef ^:pure clojure.core/dec
  [x] [number? => number?])

(>fdef ^:pure clojure.core/dissoc
  ([map] [map? => map?])
  ([map key] [map? any? => map?])
  ([map key & ks] [map? any? (s/+ any?) => map?]))

(>fdef ^:pure clojure.core/drop-last
  ([coll] [coll? => coll?])
  ([n coll] [number? coll? => coll?]))

(>fdef ^:pure clojure.core/first
  [coll] [coll? => any?])

(>fdef ^:pure clojure.core/get
  ([map key] [map? any? => any?])
  ([map key not-found] [map? any? any? => any?]))

(>fdef ^:pure clojure.core/get-in
  ([m ks] [map? (s/coll-of any? :kind vector?) => any?])
  ([m ks not-found] [map? (s/coll-of any? :kind vector?) any? => any?]))

(>fdef ^:pure clojure.core/hash
  [x] [any? => int?])

(>fdef ^:pure clojure.core/inc
  [x] [number? => number?])

(>fdef ^:pure clojure.core/inst-ms
  [inst] [inst? => int?])

(>fdef ^:pure clojure.core/keys
  [map] [map? => (s/coll-of any? :kind seq?)])

(>fdef ^:pure clojure.core/keyword
  ([name] [string? => keyword?])
  ([ns name] [string? string? => keyword?]))

(>fdef ^:pure clojure.core/key
  [e] [map-entry? => any?])

(>fdef ^:pure clojure.core/last
  [coll] [coll? => any?])

(>fdef ^:pure clojure.core/list
  [& items] [(s/* any?) => list?])

(>fdef ^:pure clojure.core/max
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/+ number?) => number?]))

(>fdef ^:pure clojure.core/merge
  [& maps] [(s/+ map?) => map?])

(>fdef ^:pure clojure.core/meta
  [obj] [any? => (? map?)])

(>fdef ^:pure clojure.core/min
  ([x] [number? => number?])
  ([x y] [number? number? => number?])
  ([x y & more] [number? number? (s/+ number?) => number?]))

(s/def ::named
  (s/or
    :string string?
    :keyword keyword?
    :symbol symbol?))

(>fdef ^:pure clojure.core/name
  [x] [::named => string?])

(>fdef ^:pure clojure.core/namespace
  [x] [::named => (? string?)])

(>fdef ^:pure clojure.core/next
  [coll] [coll? => (? coll?)])

(>fdef ^:pure clojure.core/not
  [x] [any? => boolean?])

(>fdef ^:pure clojure.core/not=
  ([x] [any? => boolean?])
  ([x y] [any? any? => boolean?])
  ([x y & more] [any? any? (s/+ any?) => boolean?]))

(>fdef ^:pure clojure.core/nth
  ([coll index] [coll? number? => any?])
  ([coll index not-found] [coll? number? any? => any?]))

(>fdef ^:pure clojure.core/qualified-ident?
  [x] [any? => boolean?])

(>fdef ^:pure clojure.core/qualified-symbol?
  [x] [any? => boolean?])

(>fdef ^:pure clojure.core/qualified-keyword?
  [x] [any? => boolean?])

(>fdef ^:pure clojure.core/quot
  [num div] [number? number? => number?])

(>fdef ^:pure clojure.core/rand
  ([] [=> number?])
  ([n] [number? => number?]))

(>fdef ^:pure clojure.core/rand-int
  [n] [int? => int?])

(>fdef ^:pure clojure.core/rand-nth
  [coll] [coll? => any?])

(>fdef ^:pure clojure.core/range
  ([] [=> (s/coll-of number? :kind seq?)])
  ([end] [number? => (s/coll-of number? :kind seq?)])
  ([start end] [number? number? => (s/coll-of number? :kind seq?)])
  ([start end step] [number? number? number? => (s/coll-of number? :kind seq?)]))

(>fdef ^:pure clojure.core/repeat
  ([x] [any? => (s/coll-of any?)])
  ([n x] [number? any? => (s/coll-of any?)]))

(>fdef ^:pure clojure.core/rest
  [coll] [coll? => (? seq?)])

(>fdef ^:pure clojure.core/reverse
  [coll] [coll? => seq?])

(>fdef ^:pure clojure.core/second
  [x] [coll? => any?])

(>fdef ^:pure clojure.core/select-keys
  [map keyseq] [map? sequential? => map?])

(>fdef ^:pure clojure.core/seq?
  [x] [any? => boolean?])

(>fdef ^:pure clojure.core/set
  [coll] [coll? => set?])

(>fdef ^:pure clojure.core/some?
  [x] [any? => boolean?])

(>fdef ^:pure clojure.core/str
  ([] [=> string?])
  ([x] [any? => string?])
  ([x & ys] [any? (s/* any?) => string?]))

(>fdef ^:pure clojure.core/symbol
  ([name] [::named => symbol?])
  ([ns name] [string? string? => symbol?]))

(>fdef ^:pure clojure.core/take-last
  [n coll] [number? coll? => coll?])

(>fdef ^:pure clojure.core/take-nth
  [n coll] [number? coll? => coll?])

(>fdef ^:pure clojure.core/val
  [e] [map-entry? => any?])

(>fdef ^:pure clojure.core/vals
  [map] [map? => (s/coll-of any? :kind seq?)])

(>fdef ^:pure clojure.core/vec
  [coll] [coll? => vector?])

(>fdef ^:pure clojure.core/vector
  [& xs] [(s/* any?) => vector?])

(>fdef ^:pure clojure.core/zipmap
  [keys vals] [coll? coll? => map?])
