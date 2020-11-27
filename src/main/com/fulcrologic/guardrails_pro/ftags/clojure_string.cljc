(ns com.fulcrologic.guardrails-pro.ftags.clojure-string
  (:require
    clojure.test.check.generators
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>fdef >fspec => | ?]]
    [com.fulcrologic.guardrails.utils :as utils]))

;; TODO: Technically in Java these types are not right, but it is how most ppl use them. Also missing
;; CLJC spec for patterns/matchers

(>fdef ^:pure reverse [s] [string? => string?])
(>fdef ^:pure re-quote-replacement [replacement] [string? => string?])
(>fdef ^:pure ^String replace [s match replacement] [string? any? string? => string?])
(>fdef ^:pure replace-first [s match replacement] [string? any? string? => string?])
(>fdef ^:pure join ([coll] [(s/coll-of any?) => string?])
  ([separator coll] [string? (s/coll-of any?) => string?]))
(>fdef ^:pure capitalize [s] [string? => string?])
(>fdef ^:pure upper-case [s] [string? => string?])
(>fdef ^:pure lower-case [s] [string? => string?])
(>fdef ^:pure split ([s re] [string? any? => (s/coll-of string?)])
  ([s re limit] [string? any? pos-int? => (s/coll-of string?)]))
(>fdef ^:pure split-lines [s] [string? => (s/coll-of string?)])
(>fdef ^:pure trim [s] [string? => string?])
(>fdef ^:pure triml [s] [string? => string?])
(>fdef ^:pure trimr [s] [string? => string?])
(>fdef ^:pure trim-newline [s] [string? => string?])
(>fdef ^:pure blank? [s] [string? => boolean?])
(>fdef ^:pure escape [^CharSequence s cmap] [string? (s/map-of char? char?) => string?])
(>fdef ^:pure index-of
  ([s value] [string? (s/or :c char? :s string?) => (s/int-in -1 Integer/MAX_VALUE)])
  ([s value from-index] [string? (s/or :c char? :s string?) nat-int? => (s/int-in -1 Integer/MAX_VALUE)]))
(>fdef ^:pure last-index-of
  ([s value] [string? (s/or :c char? :s string?) => (s/int-in -1 Integer/MAX_VALUE)])
  ([s value from-index] [string? (s/or :c char? :s string?) nat-int? => (s/int-in -1 Integer/MAX_VALUE)]))
(>fdef ^:pure starts-with? [s substr] [string? string? => boolean?])
(>fdef ^:pure ends-with? [s substr] [string? string? => boolean?])
(>fdef ^:pure includes? [s substr] [string? string? => boolean?])