(ns com.fulcrologic.guardrails.static.forms
  #?(:cljs (:require-macros [com.fulcrologic.guardrails.static.forms :refer [>defn]]))
  (:require
    [clojure.spec.alpha :as s]
    #?(:cljs ["react" :as react]))
  #?(:clj
     (:import (clojure.lang IMeta))))

#?(:clj
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
           :else form)))))

(def memory (atom {}))

(comment
  ;; look at the meta on the CLJC conditional in the let
  (-> @memory (get 'f) (nth 3) second (nth 5) meta))

(defn remember! [s form]
  (swap! memory assoc s form))

#?(:clj
   (defmacro >defn [sym args & body]
     `(do
        (defn ~sym ~args ~@body)
        (remember! (quote ~sym) ~(copy-form &form)))))

(>defn f [v]
  (let [{::keys [a]} v
        c #(+ a %)
        b #?(:clj (+ a 2) :cljs (.boo react/thing a))]
    (+ a b)))

