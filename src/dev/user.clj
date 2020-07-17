(ns user
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn >def => | ?]]
    [clojure.tools.namespace.repl :as tools-ns]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log])
  (:import (clojure.lang Cons)))

(def the-form '(let [a (f 2)]
                 a))

(defn process-forms [action env forms]
  (reduce
    (fn [[env new-forms] form]
      (let [{:keys [form] :as e2} (action (assoc env :form form))]
        [e2 (conj new-forms form)]))
    [env []]
    forms))

(declare parse)

(defn process-binding [env] (update-in (log/spy :info env) [:form 0] vary-meta assoc :visited? true))

(defn check-let [{:keys [form] :as env}]
  (let [[kw bindings & body] form
        binding-pairs (map vec (partition 2 bindings))
        [new-env new-binding-pairs] (process-forms process-binding env binding-pairs)
        [final-env new-body] (process-forms parse new-env body)
        new-bindings  (vec (apply concat new-binding-pairs))]
    (assoc final-env :form (with-meta (apply list kw new-bindings new-body)
                             {:results 42}))))

(defn parse-call [{:keys [form] :as env}]
  (if (= 'let (first form))
    (let [updated-form (vary-meta form assoc :checked? true)
          new-env      (-> env
                         (update :seen (fnil conj []) {:call 'let})
                         (assoc :form updated-form))]
      (check-let new-env))))

(defn parse [{:keys [form] :as env}]
  (cond
    (or
      (instance? Cons form)
      (list? form)) (parse-call env)
    :else env))

(comment
  (-> (parse {:form the-form}) :form second first meta))

