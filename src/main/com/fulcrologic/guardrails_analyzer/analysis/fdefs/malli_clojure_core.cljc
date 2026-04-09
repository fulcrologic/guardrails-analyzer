(ns com.fulcrologic.guardrails-analyzer.analysis.fdefs.malli-clojure-core
  (:require
   [com.fulcrologic.guardrails.malli.core :refer [=> >fdef ?]]
   [com.fulcrologic.guardrails.malli.registry :as reg]
   [malli.core])
  (:import
   #?(:clj (java.util.regex Pattern))))

;; Register predicate schemas that malli doesn't have built-in.
;; This lets us use keywords like :number instead of [:fn :number]
;; which the guardrails parser would quote as a symbol.
(reg/register! :number (malli.core/-simple-schema {:type :number :pred number?}))
(reg/register! :coll (malli.core/-simple-schema {:type :coll :pred coll?}))
(reg/register! :seqable (malli.core/-simple-schema {:type :seqable :pred seqable?}))
(reg/register! :ifn (malli.core/-simple-schema {:type :ifn :pred ifn?}))
(reg/register! :atom (malli.core/-simple-schema {:type :atom :pred #(instance? clojure.lang.Atom %)}))
(reg/register! :nat-int (malli.core/-simple-schema {:type :nat-int :pred nat-int?}))
(reg/register! :pos-int (malli.core/-simple-schema {:type :pos-int :pred pos-int?}))
(reg/register! :seq (malli.core/-simple-schema {:type :seq :pred seq?}))
(reg/register! :list (malli.core/-simple-schema {:type :list :pred list?}))
(reg/register! :map-entry (malli.core/-simple-schema {:type :map-entry :pred map-entry?}))
(reg/register! :char (malli.core/-simple-schema {:type :char :pred char?}))
#?(:clj (reg/register! :regex (malli.core/-simple-schema {:type :regex :pred (fn [x] (= (type x) Pattern))})))
#?(:clj (reg/register! :inst (malli.core/-simple-schema {:type :inst :pred inst?})))

;; Malli fdefs for clojure.core functions.
;; These are the malli equivalents of the spec1 fdefs in clojure_core.cljc.
;; The analyzer uses these when analyzing malli >defn bodies, so that
;; type descriptions stay in the malli world end-to-end.

;; NOTE: Higher-order functions (map, reduce, comp, partial, etc.) are omitted
;; here because they require >fspec which is not yet supported in malli fdefs.
;; They fall back to the spec1 fdefs via cross-system switching.

;; Arithmetic

(>fdef ^:pure clojure.core/+
       ([] [=> :number])
       ([x] [:number => :number])
       ([x y] [:number :number => :number])
       ([x y & more] [:number :number [:+ :number] => :number]))

(>fdef ^:pure clojure.core/-
       ([] [=> :number])
       ([x] [:number => :number])
       ([x y] [:number :number => :number])
       ([x y & more] [:number :number [:+ :number] => :number]))

(>fdef ^:pure clojure.core/*
       ([] [=> :number])
       ([x] [:number => :number])
       ([x y] [:number :number => :number])
       ([x y & more] [:number :number [:+ :number] => :number]))

(>fdef ^:pure clojure.core//
       ([x] [:number => :number])
       ([x y] [:number :number => :number])
       ([x y & more] [:number :number [:+ :number] => :number]))

(>fdef ^:pure clojure.core/inc
       [x] [:number => :number])

(>fdef ^:pure clojure.core/dec
       [x] [:number => :number])

(>fdef ^:pure clojure.core/quot
       [num div] [:number :number => :number])

(>fdef ^:pure clojure.core/max
       ([x] [:number => :number])
       ([x y] [:number :number => :number])
       ([x y & more] [:number :number [:+ :number] => :number]))

(>fdef ^:pure clojure.core/min
       ([x] [:number => :number])
       ([x y] [:number :number => :number])
       ([x y & more] [:number :number [:+ :number] => :number]))

(>fdef ^:pure clojure.core/rand
       ([] [=> :number])
       ([n] [:number => :number]))

(>fdef ^:pure clojure.core/rand-int
       [n] [:int => :int])

;; Comparisons

(>fdef ^:pure clojure.core/=
       ([x] [:any => :boolean])
       ([x y] [:any :any => :boolean])
       ([x y & more] [:any :any [:+ :any] => :boolean]))

(>fdef ^:pure clojure.core/==
       ([x] [:any => :boolean])
       ([x y] [:any :any => :boolean])
       ([x y & more] [:any :any [:+ :any] => :boolean]))

(>fdef ^:pure clojure.core/<
       ([x] [:any => :boolean])
       ([x y] [:any :any => :boolean])
       ([x y & more] [:any :any [:+ :any] => :boolean]))

(>fdef ^:pure clojure.core/<=
       ([x] [:any => :boolean])
       ([x y] [:any :any => :boolean])
       ([x y & more] [:any :any [:+ :any] => :boolean]))

(>fdef ^:pure clojure.core/>
       ([x] [:any => :boolean])
       ([x y] [:any :any => :boolean])
       ([x y & more] [:any :any [:+ :any] => :boolean]))

(>fdef ^:pure clojure.core/>=
       ([x] [:any => :boolean])
       ([x y] [:any :any => :boolean])
       ([x y & more] [:any :any [:+ :any] => :boolean]))

(>fdef ^:pure clojure.core/not=
       ([x] [:any => :boolean])
       ([x y] [:any :any => :boolean])
       ([x y & more] [:any :any [:+ :any] => :boolean]))

;; Predicates

(>fdef ^:pure clojure.core/nil?
       [x] [:any => :boolean])

(>fdef ^:pure clojure.core/not
       [x] [:any => :boolean])

(>fdef ^:pure clojure.core/some?
       [x] [:any => :boolean])

(>fdef ^:pure clojure.core/even?
       [n] [:int => :boolean])

(>fdef ^:pure clojure.core/odd?
       [n] [:int => :boolean])

(>fdef ^:pure clojure.core/pos?
       [n] [:number => :boolean])

(>fdef ^:pure clojure.core/coll?
       [x] [:coll => :boolean])

(>fdef ^:pure clojure.core/map?
       [x] [:any => :boolean])

(>fdef ^:pure clojure.core/seq?
       [x] [:any => :boolean])

(>fdef ^:pure clojure.core/vector?
       [x] [:any => :boolean])

(>fdef ^:pure clojure.core/string?
       [x] [:any => :boolean])

(>fdef ^:pure clojure.core/keyword?
       [x] [:any => :boolean])

(>fdef ^:pure clojure.core/symbol?
       [x] [:any => :boolean])

(>fdef ^:pure clojure.core/qualified-ident?
       [x] [:any => :boolean])

(>fdef ^:pure clojure.core/qualified-symbol?
       [x] [:any => :boolean])

(>fdef ^:pure clojure.core/qualified-keyword?
       [x] [:any => :boolean])

;; String functions

(>fdef ^:pure clojure.core/str
       ([] [=> :string])
       ([x] [:any => :string])
       ([x & ys] [:any [:* :any] => :string]))

(>fdef ^:pure clojure.core/pr-str
       [& xs] [[:* :any] => :string])

(>fdef ^:pure clojure.core/print-str
       [& xs] [[:* :any] => :string])

(>fdef ^:pure clojure.core/println-str
       [& xs] [[:* :any] => :string])

(>fdef ^:pure clojure.core/subs
       ([s start] [:string :nat-int => :string])
       ([s start end] [:string :nat-int :nat-int => :string]))

(>fdef ^:pure clojure.core/name
       [x] [[:or :string :keyword :symbol] => :string])

(>fdef ^:pure clojure.core/namespace
       [x] [[:or :string :keyword :symbol] => (? :string)])

;; IO (side-effecting)

(>fdef clojure.core/pr
       [& xs] [[:* :any] => :nil])

(>fdef clojure.core/prn
       [& xs] [[:* :any] => :nil])

(>fdef clojure.core/print
       [& xs] [[:* :any] => :nil])

(>fdef clojure.core/println
       [& xs] [[:* :any] => :nil])

#?(:clj
   (>fdef clojure.core/printf
          [fmt & xs] [:string [:* :any] => :nil]))

;; Collections

(>fdef ^:pure clojure.core/count
       [coll] [:coll => :pos-int])

(>fdef ^:pure clojure.core/first
       [coll] [:coll => :any])

(>fdef ^:pure clojure.core/second
       [x] [:coll => :any])

(>fdef ^:pure clojure.core/last
       [coll] [:coll => :any])

(>fdef ^:pure clojure.core/next
       [coll] [:coll => (? :coll)])

(>fdef ^:pure clojure.core/rest
       [coll] [:coll => (? :seq)])

(>fdef ^:pure clojure.core/butlast
       [coll] [:coll => :coll])

(>fdef ^:pure clojure.core/reverse
       [coll] [:coll => :seq])

(>fdef ^:pure clojure.core/seq
       [coll] [:coll => (? :coll)])

(>fdef ^:pure clojure.core/sort
       ([coll] [:coll => :coll])
       ([comp coll] [:some :coll => :coll]))

(>fdef ^:pure clojure.core/concat
       ([] [=> :seq])
       ([x] [:coll => :seq])
       ([x y] [:coll :coll => :seq])
       ([x y & zs] [:coll :coll [:+ :coll] => :seq]))

(>fdef ^:pure clojure.core/conj
       ([coll x] [:coll :any => :coll])
       ([coll x & xs] [:coll :any [:+ :any] => :coll]))

(>fdef ^:pure clojure.core/cons
       [x seq] [:any :coll => :seq])

(>fdef ^:pure clojure.core/contains?
       [coll key] [:coll :any => :boolean])

(>fdef ^:pure clojure.core/nth
       ([coll index] [:coll :number => :any])
       ([coll index not-found] [:coll :number :any => :any]))

(>fdef ^:pure clojure.core/take-last
       [n coll] [:number :coll => :coll])

(>fdef ^:pure clojure.core/take-nth
       [n coll] [:number :coll => :coll])

(>fdef ^:pure clojure.core/drop-last
       ([coll] [:coll => :coll])
       ([n coll] [:number :coll => :coll]))

(>fdef ^:pure clojure.core/rand-nth
       [coll] [:coll => :any])

(>fdef ^:pure clojure.core/range
       ([] [=> [:sequential :number]])
       ([end] [:number => [:sequential :number]])
       ([start end] [:number :number => [:sequential :number]])
       ([start end step] [:number :number :number => [:sequential :number]]))

(>fdef ^:pure clojure.core/repeat
       ([x] [:any => [:sequential :any]])
       ([n x] [:number :any => [:sequential :any]]))

(>fdef ^:pure clojure.core/list
       [& items] [[:* :any] => :list])

(>fdef ^:pure clojure.core/vec
       [coll] [:coll => :vector])

(>fdef ^:pure clojure.core/vector
       [& xs] [[:* :any] => :vector])

(>fdef ^:pure clojure.core/set
       [coll] [:coll => [:set :any]])

(>fdef ^:pure clojure.core/zipmap
       [keys vals] [:coll :coll => :map])

;; Map operations

(>fdef ^:pure clojure.core/assoc
       ([coll k v] [:map :any :any => :map])
       ([coll k v & kvs] [:map :any :any [:* :any] => :map]))

(>fdef ^:pure clojure.core/assoc-in
       [m ks v] [:map [:+ :any] :any => :map])

(>fdef ^:pure clojure.core/dissoc
       ([map] [:map => :map])
       ([map key] [:map :any => :map])
       ([map key & ks] [:map :any [:+ :any] => :map]))

(>fdef ^:pure clojure.core/get
       ([map key] [:map :any => :any])
       ([map key not-found] [:map :any :any => :any]))

(>fdef ^:pure clojure.core/get-in
       ([m ks] [:map [:sequential :any] => :any])
       ([m ks not-found] [:map [:sequential :any] :any => :any]))

(>fdef ^:pure clojure.core/keys
       [map] [:map => [:sequential :any]])

(>fdef ^:pure clojure.core/vals
       [map] [:map => [:sequential :any]])

(>fdef ^:pure clojure.core/key
       [e] [:map-entry => :any])

(>fdef ^:pure clojure.core/val
       [e] [:map-entry => :any])

(>fdef ^:pure clojure.core/merge
       [& maps] [[:+ :map] => :map])

(>fdef ^:pure clojure.core/select-keys
       [map keyseq] [:map [:sequential :any] => :map])

(>fdef ^:pure clojure.core/update
       [m k f & args] [:map :any :ifn [:* :any] => :map])

;; Misc

(>fdef ^:pure clojure.core/hash
       [x] [:any => :int])

(>fdef ^:pure clojure.core/type
       [x] [:any => :any])

(>fdef ^:pure clojure.core/meta
       [obj] [:any => (? :map)])

(>fdef ^:pure clojure.core/atom
       [value] [:any => :atom])

(>fdef clojure.core/swap!
       [a f & args] [:atom :ifn [:* :any] => :any])

(>fdef ^:pure clojure.core/keyword
       ([name] [:string => :keyword])
       ([ns name] [:string :string => :keyword]))

(>fdef ^:pure clojure.core/symbol
       ([name] [[:or :string :keyword :symbol] => :symbol])
       ([ns name] [:string :string => :symbol]))

#?(:clj
   (>fdef ^:pure clojure.core/inst-ms
          [inst] [:inst => :int]))

(>fdef ^:pure clojure.core/re-find
       [re s] [:regex :string => (? :string)])

(>fdef ^:pure clojure.core/re-seq
       [re s] [:regex :string => [:sequential :string]])
