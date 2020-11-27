(ns com.fulcrologic.guardrails-pro.ftags.clojure-string
  (:require
    clojure.test.check.generators
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>fdef >fspec => | ?]]
    [com.fulcrologic.guardrails.utils :as utils]))

;; TODO: Technically in Java these types are not right, but it is how most ppl use them. Also missing
;; CLJC spec for patterns/matchers

(>fdef ^:pure clojure.string/reverse [s] [string? => string?])
(>fdef ^:pure clojure.string/re-quote-replacement [replacement] [string? => string?])
(>fdef ^:pure clojure.string/replace [s match replacement] [string? any? string? => string?])
(>fdef ^:pure clojure.string/replace-first [s match replacement] [string? any? string? => string?])
(>fdef ^:pure clojure.string/join ([coll] [(s/coll-of any?) => string?])
  ([separator coll] [string? (s/coll-of any?) => string?]))
(>fdef ^:pure clojure.string/capitalize [s] [string? => string?])
(>fdef ^:pure clojure.string/upper-case [s] [string? => string?])
(>fdef ^:pure clojure.string/lower-case [s] [string? => string?])
(>fdef ^:pure clojure.string/split ([s re] [string? any? => (s/coll-of string?)])
  ([s re limit] [string? any? pos-int? => (s/coll-of string?)]))
(>fdef ^:pure clojure.string/split-lines [s] [string? => (s/coll-of string?)])
(>fdef ^:pure clojure.string/trim [s] [string? => string?])
(>fdef ^:pure clojure.string/triml [s] [string? => string?])
(>fdef ^:pure clojure.string/trimr [s] [string? => string?])
(>fdef ^:pure clojure.string/trim-newline [s] [string? => string?])
(>fdef ^:pure clojure.string/blank? [s] [string? => boolean?])
(>fdef ^:pure clojure.string/escape [^CharSequence s cmap] [string? (s/map-of char? char?) => string?])
(>fdef ^:pure clojure.string/index-of
  ([s value] [string? (s/or :c char? :s string?) => (s/int-in -1 Integer/MAX_VALUE)])
  ([s value from-index] [string? (s/or :c char? :s string?) nat-int? => (s/int-in -1 Integer/MAX_VALUE)]))
(>fdef ^:pure clojure.string/last-index-of
  ([s value] [string? (s/or :c char? :s string?) => (s/int-in -1 Integer/MAX_VALUE)])
  ([s value from-index] [string? (s/or :c char? :s string?) nat-int? => (s/int-in -1 Integer/MAX_VALUE)]))
(>fdef ^:pure clojure.string/starts-with? [s substr] [string? string? => boolean?])
(>fdef ^:pure clojure.string/ends-with? [s substr] [string? string? => boolean?])
(>fdef ^:pure clojure.string/includes? [s substr] [string? string? => boolean?])