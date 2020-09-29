(ns com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch
  (:require
    [com.fulcrologic.guardrails.core :as gr :refer [>defn =>]]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [taoensso.timbre :as log])
  #?(:clj (:import (java.util.regex Pattern))))

(declare analyze-mm)

(defn regex? [x]
  #?(:clj  (= (type x) Pattern)
     :cljs (regexp? x)))

(defn list-dispatch [env [head :as _sexpr]]
  (letfn [(symbol-dispatch [sym]
            (cond
              (grp.art/function-detail env sym)
              #_=> :function/call
              (grp.art/symbol-detail env sym)
              #_=> :symbol.local/lookup
              (and (namespace sym) (get (methods analyze-mm) (grp.art/cljc-rewrite-sym-ns sym)))
              #_=> (grp.art/cljc-rewrite-sym-ns sym)
              (get (methods analyze-mm) sym)
              #_=> sym
              (grp.art/external-function-detail env sym)
              #_=> :function.external/call
              :else :ifn/call))]
    (cond
      (seq? head) :function.expression/call
      (symbol? head) (symbol-dispatch head)
      (ifn? head) :ifn/call
      :else :unknown)))

(defn analyze-dispatch [env sexpr]
  (cond
    (seq? sexpr) (list-dispatch env sexpr)
    (symbol? sexpr) :symbol.local/lookup

    (char? sexpr) :literal/char
    (number? sexpr) :literal/number
    (string? sexpr) :literal/string
    (keyword? sexpr) :literal/keyword
    (regex? sexpr) :literal/regex
    (nil? sexpr) :literal/nil

    (vector? sexpr) :collection/vector
    (set? sexpr) :collection/set
    (map? sexpr) :collection/map

    :else :unknown))

(defmulti analyze-mm
  (fn [env sexpr]
    (log/spy :info :dispatch
      (analyze-dispatch env sexpr)))
  :default :unknown)

(>defn -analyze!
  [env sexpr]
  [::grp.art/env any? => ::grp.art/type-description]
  (log/info "analyzing:" (pr-str sexpr) ", meta:" (meta sexpr))
  (log/spy :debug "analyze! returned:"
    (-> env
      (grp.art/update-location (meta sexpr))
      (analyze-mm sexpr))))
