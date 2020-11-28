(ns com.fulcrologic.copilot.analysis.analyzer.dispatch
  (:require
    [com.fulcrologic.guardrails.core :as gr :refer [>defn =>]]
    [com.fulcrologic.copilot.analytics :as grp.analytics]
    [com.fulcrologic.copilot.artifacts :as grp.art])
  #?(:clj (:import (java.util.regex Pattern))))

(declare analyze-mm)

(defn regex? [x]
  #?(:clj  (= (type x) Pattern)
     :cljs (regexp? x)))

(defn quoted-symbol? [x]
  (and (seq? x) (seq x)
    (= 'quote (first x))
    (symbol? (second x))))

(defn qualify-symbol [env sym]
  (if (special-symbol? sym)
    (symbol "clojure.core" (name sym))
    (grp.art/qualify-extern env sym)))

(defn list-dispatch [env [head :as _sexpr]]
  (letfn [(symbol-dispatch [sym]
            (let [cljc-symbol (qualify-symbol env sym)]
              (cond
                ;; NOTE: a local symbol resolves first
                (grp.art/symbol-detail env sym)
                #_=> :symbol.local/lookup
                ;; NOTE: analyze methods resolve before any >defn / >ftag definitions
                (get (methods analyze-mm) cljc-symbol)
                #_=> cljc-symbol
                ;; NOTE: >defn resolves before >ftag
                (grp.art/function-detail env sym)
                #_=> :function/call
                (grp.art/external-function-detail env sym)
                #_=> :function.external/call
                (quoted-symbol? sym) :ifn/call
                :else :unknown)))]
    (cond
      (symbol? head) (symbol-dispatch head)
      (quoted-symbol? head) :ifn/call
      (seq? head) :function.expression/call
      (ifn? head) :ifn/call
      :else :unknown)))

(defn analyze-dispatch [env sexpr]
  (cond
    (seq? sexpr) (list-dispatch env sexpr)
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

    (ifn? sexpr) :ifn/literal

    :else :unknown))

(defmulti analyze-mm (fn [env _] (::dispatch env)) :default :unknown)

(>defn -analyze!
  [env sexpr]
  [::grp.art/env any? => ::grp.art/type-description]
  (let [dispatch (analyze-dispatch env sexpr)]
    (grp.analytics/with-analytics env sexpr
      (and (qualified-symbol? dispatch)
        (#{"clojure.core"} (namespace dispatch)))
      #(as-> % env
         (assoc env ::dispatch dispatch)
         (grp.art/update-location env (meta sexpr))
         (grp.analytics/profile ::analyze-mm
           (analyze-mm env sexpr))))))

(defn analyze-statements! [env body]
  (doseq [expr (butlast body)]
    (-analyze! env expr))
  (-analyze! env (last body)))

(defn unknown-expr [env sexpr]
  (grp.art/record-info! env sexpr :info/failed-to-analyze-unknown-expression)
  {::grp.art/unknown-expression sexpr})
