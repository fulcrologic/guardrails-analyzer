(ns com.fulcrologic.guardrails.static.forms
  (:require [clojure.spec.alpha :as s])
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

(def memory (atom {}))

(defn remember! [s form]
  (swap! memory assoc s form))

(defmacro >defn [sym args & body]
  `(do
     (defn ~sym ~args ~@body)
     (remember! (quote ~sym) (quote ~(copy-form &form)))))

(>defn f [v]
  (let [{::keys [a]} v
        c #(+ a %)
        b (+ a 2)]
    (+ a b)))

