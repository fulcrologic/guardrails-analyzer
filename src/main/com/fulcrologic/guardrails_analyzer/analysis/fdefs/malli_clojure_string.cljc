(ns com.fulcrologic.guardrails-analyzer.analysis.fdefs.malli-clojure-string
  (:require
   [com.fulcrologic.guardrails.malli.core :refer [=> >fdef]]))

;; Malli fdefs for clojure.string functions.
;; These are the malli equivalents of the spec1 fdefs in clojure_string.cljc.

(>fdef ^:pure clojure.string/reverse [s] [:string => :string])
(>fdef ^:pure clojure.string/replace [s match replacement] [:string :any :string => :string])
(>fdef ^:pure clojure.string/replace-first [s match replacement] [:string :any :string => :string])
(>fdef ^:pure clojure.string/join
       ([coll] [[:sequential :any] => :string])
       ([separator coll] [:string [:sequential :any] => :string]))
(>fdef ^:pure clojure.string/capitalize [s] [:string => :string])
(>fdef ^:pure clojure.string/upper-case [s] [:string => :string])
(>fdef ^:pure clojure.string/lower-case [s] [:string => :string])
(>fdef ^:pure clojure.string/split
       ([s re] [:string :any => [:sequential :string]])
       ([s re limit] [:string :any :pos-int => [:sequential :string]]))
(>fdef ^:pure clojure.string/split-lines [s] [:string => [:sequential :string]])
(>fdef ^:pure clojure.string/trim [s] [:string => :string])
(>fdef ^:pure clojure.string/triml [s] [:string => :string])
(>fdef ^:pure clojure.string/trimr [s] [:string => :string])
(>fdef ^:pure clojure.string/trim-newline [s] [:string => :string])
(>fdef ^:pure clojure.string/blank? [s] [:string => :boolean])
(>fdef ^:pure clojure.string/escape [s cmap] [:string [:map-of :any :any] => :string])
(>fdef ^:pure clojure.string/index-of
       ([s value] [:string [:or :char :string] => :int])
       ([s value from-index] [:string [:or :char :string] :nat-int => :int]))
(>fdef ^:pure clojure.string/last-index-of
       ([s value] [:string [:or :char :string] => :int])
       ([s value from-index] [:string [:or :char :string] :nat-int => :int]))
(>fdef ^:pure clojure.string/starts-with? [s substr] [:string :string => :boolean])
(>fdef ^:pure clojure.string/ends-with? [s substr] [:string :string => :boolean])
(>fdef ^:pure clojure.string/includes? [s substr] [:string :string => :boolean])
