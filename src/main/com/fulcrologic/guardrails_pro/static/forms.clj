(ns com.fulcrologic.guardrails-pro.static.forms
  "Algorithms related to processing forms during macro processing."
  (:import (clojure.lang IMeta Cons)))

(defn form-expression
  "Converts the given form into a runtime expression that will re-create the form (including metadata)."
  [form]
  (let [md (when (instance? IMeta form) (meta form))]
    (if md
      (cond
        (or (instance? Cons form)
          (list? form)) `(with-meta (list ~@(map form-expression form)) ~md)
        (vector? form) `(with-meta ~(mapv form-expression form) ~md)
        (map? form) `(with-meta ~(into {} (reduce-kv
                                            (fn [r k v] (assoc r k (form-expression v)))
                                            {}
                                            form))
                       ~md)
        (symbol? form) `(with-meta (quote ~form) ~md)
        (instance? IMeta form) `(with-meta ~form ~md)
        :else form)
      (cond
        (or (instance? Cons form)
          (list? form)) `(list ~@(map form-expression form))
        (vector? form) (mapv form-expression form)
        (map? form) (into {} (reduce-kv
                               (fn [r k v] (assoc r k (form-expression v)))
                               {}
                               form))
        (symbol? form) `(quote ~form)
        :else form))))

