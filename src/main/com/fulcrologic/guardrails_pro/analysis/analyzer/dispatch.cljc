(ns com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch
  (:require
    [com.fulcrologic.guardrails.core :as gr :refer [>defn =>]]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [taoensso.timbre :as log]
    [taoensso.tufte :refer [p]])
  #?(:clj (:import (java.util.regex Pattern))))

(declare analyze-mm)

(defn regex? [x]
  #?(:clj  (= (type x) Pattern)
     :cljs (regexp? x)))

(defn quoted-symbol? [x]
  (and (seq? x) (seq x)
    (= 'quote (first x))
    (symbol? (second x))))

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
      (symbol? head) (symbol-dispatch head)
      (quoted-symbol? head) :ifn/call
      (seq? head) :function.expression/call
      (ifn? head) :ifn/call
      :else :unknown)))

(defn analyze-dispatch [env sexpr]
  (cond
    (seq? sexpr) (p ::list-dispatch (list-dispatch env sexpr))
    (symbol? sexpr) :symbol/lookup

    (nil? sexpr) :literal/nil
    (char? sexpr) :literal/char
    (string? sexpr) :literal/string
    (regex? sexpr) :literal/regex
    (number? sexpr) :literal/number
    (keyword? sexpr) :literal/keyword

    (vector? sexpr) :collection/vector
    (set? sexpr) :collection/set
    (map? sexpr) :collection/map

    :else :unknown))

(defmulti analyze-mm
  (fn [env sexpr]
    (log/spy :info :dispatch
      (p ::compute-dispatch
        (analyze-dispatch env sexpr))))
  :default :unknown)

(>defn -analyze!
  [env sexpr]
  [::grp.art/env any? => ::grp.art/type-description]
  (log/info "analyzing:" (pr-str sexpr) ", meta:" (meta sexpr))
  (log/spy :debug (str "analyze! " (pr-str sexpr)
                    " dispatched to: " (analyze-dispatch env sexpr)
                    " returned:")
    (p ::-analyze!
      (-> env
        (grp.art/update-location (meta sexpr))
        (analyze-mm sexpr)))))

(defn analyze-statements! [env body]
  (doseq [expr (butlast body)]
    (-analyze! env expr))
  (-analyze! env (last body)))

(defn unknown-expr [env sexpr]
  (grp.art/record-info! env sexpr :info/failed-to-analyze-unknown-expression)
  {::grp.art/unknown-expression sexpr})
