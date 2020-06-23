(ns com.fulcrologic.guardrails-pro.static.forms
  "Algorithms related to processing forms during macro processing."
  (:import (clojure.lang IMeta)))

(defn copy-form [form]
  (let [md (when (instance? IMeta form) (meta form))]
    (if md
      (cond
        (list? form) `(with-meta (list ~@(map copy-form form)) ~md)
        (vector? form) `(with-meta ~(mapv copy-form form) ~md)
        (map? form) `(with-meta ~(into {} (reduce-kv
                                            (fn [r k v] (assoc r k (copy-form v)))
                                            {}
                                            form))
                       ~md)
        (symbol? form) `(with-meta (quote ~form) ~md)
        (instance? IMeta form) `(with-meta ~form ~md)
        :else form)
      (cond
        (list? form) `(list ~@(map copy-form form))
        (vector? form) (mapv copy-form form)
        (map? form) (into {} (reduce-kv
                               (fn [r k v] (assoc r k (copy-form v)))
                               {}
                               form))
        (symbol? form) `(quote ~form)
        :else form))))

(comment
  (binding [*compile-path* "/Users/tonykay/fulcrologic/guardrails-pro/src/main"]
    (compile 'com.fulcrologic.guardrails-pro.static.forms)
    (compile 'com.fulcrologic.guardrails-pro.static.checker)
    (compile 'com.fulcrologic.guardrails-pro.runtime.artifacts)
    ))
