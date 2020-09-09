(ns com.fulcrologic.guardrails-pro.static.forms
  "Algorithms related to processing forms during macro processing."
  (:require
    [taoensso.encore :as enc]))

(defn form-expression
  "Converts the given form into a runtime expression that will re-create the form (including metadata)."
  [form]
  (let [x (cond
            (seq? form)    `(list ~@(map form-expression form))
            (vector? form) (mapv form-expression form)
            (map? form)    (enc/map-vals form-expression form)
            (symbol? form) `(quote ~form)
            :else          form)]
    (if-let [m (meta form)]
      `(with-meta ~x ~m)
      x)))
