(ns com.fulcrologic.copilot.analysis.analyzer.dispatch
  (:require
    [com.fulcrologic.copilot.analytics :as cp.analytics]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn =>]]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(declare analyze-mm)

(defn quoted-symbol? [x]
  (and (seq? x) (seq x)
    (= 'quote (first x))
    (symbol? (second x))))

(defn qualify-symbol [env sym]
  (if (special-symbol? sym)
    (symbol "clojure.core" (name sym))
    (cp.art/qualify-extern env sym)))

(defn list-dispatch [env [head :as _sexpr]]
  (letfn [(symbol-dispatch [sym]
            (let [cljc-symbol (qualify-symbol env sym)
                  core-symbol (when-not (namespace sym)
                                (symbol "clojure.core" (name sym)))]
              (cond
                ;; NOTE: a local symbol resolves first
                (cp.art/symbol-detail env sym)
                #_=> :symbol.local/lookup
                ;; NOTE: analyze methods resolve before any >defn / >ftag definitions
                (get (methods analyze-mm) cljc-symbol)
                #_=> cljc-symbol
                (when core-symbol (get (methods analyze-mm) core-symbol))
                #_=> core-symbol
                ;; NOTE: >defn resolves before >ftag
                (cp.art/function-detail env sym)
                #_=> :function/call
                (cp.art/external-function-detail env sym)
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

    (and (map? sexpr) (:com.fulcrologic.copilot/meta-wrapper? sexpr)) :literal/wrapped

    (vector? sexpr) :collection/vector
    (set? sexpr) :collection/set
    (map? sexpr) :collection/map

    (ifn? sexpr) :ifn/literal

    :else :unknown))

(defmulti analyze-mm (fn [env _] (::dispatch env)) :default :unknown)

(>defn -analyze!
  [env sexpr]
  [::cp.art/env any? => ::cp.art/type-description]
  (let [dispatch (analyze-dispatch env sexpr)]
    (cp.analytics/with-analytics env sexpr
      (and (qualified-symbol? dispatch)
        (#{"clojure.core"} (namespace dispatch)))
      #(as-> % env
         (assoc env ::dispatch dispatch)
         (cp.art/update-location env (meta sexpr))
         (cp.analytics/profile ::analyze-mm
           (analyze-mm env sexpr))))))

(defn analyze-statements! [env body]
  (doseq [expr (butlast body)]
    (-analyze! env expr))
  (-analyze! env (last body)))

(defn unknown-expr [env sexpr]
  (cp.art/record-info! env sexpr :info/failed-to-analyze-unknown-expression)
  {::cp.art/unknown-expression sexpr})
