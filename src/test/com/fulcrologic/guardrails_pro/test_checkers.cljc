(ns com.fulcrologic.guardrails-pro.test-checkers
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test :as t]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [fulcro-spec.core :refer [specification assertions =fn=>]]))

(defn check-error? [x] (map? x))

(defn all* [& checkers]
  (fn [actual]
    (filter check-error?
      (flatten
        (map #(% actual) checkers)))))

(defn is?* [predicate]
  (fn [actual]
    (when-not (predicate actual)
      {:actual actual
       :expected predicate})))

(defn equals?* [expected]
  (fn [actual]
    (when-not (= expected actual)
      {:actual actual
       :expected expected})))

(defn valid?* [spec]
  (fn [actual]
    (when-not (s/valid? spec actual)
      {:message (s/explain-str spec actual)
       :actual actual
       :expected spec})))

(defn embeds?*
  ([expected] (embeds?* expected []))
  ([expected path]
   (fn [actual]
     (seq
       (for [[k v] expected]
         (let [actual-value (get actual k)
               path (conj path k)]
           (cond
             (map? v) #_=> ((embeds?* v path) actual-value)
             (not= actual-value v)
             #_=> {:actual actual-value
                   :expected v
                   :message (str "at path " path ":")})))))))

(defn check! [& checkers]
  (fn [actual]
    (when-let [errors ((apply all* checkers) actual)]
      (doseq [e errors]
          (t/do-report
            (assoc e :type :fail))))
    ;; return true to silence =fn=> reporting
    true))

;; NOTE: not run because file name is not a *_spec
(specification "check!"
  (let [x-double? (fn [actual]
                    (when-not (double? (get-in actual [:x]))
                      {:actual actual
                       :expected `double?
                       :message "x was not a double"}))
        failing-checker (fn [actual]
                          (vector
                            (let [v (get-in actual [:FAKE/int])]
                              (when-not (int? v)
                                {:actual v, :expected `int?
                                 :message ":FAKE/int was not an int"}))
                            (let [v (get-in actual [:FAKE/string])]
                              (when-not (string? v)
                                {:actual v, :expected `string?
                                 :message ":FAKE/string was not an string"}))))
        data {:a 1 :b {:c 2 :d 3}}]
    (assertions
      123  =fn=> (check! (equals?* 456))
      222  =fn=> (check! (is?* odd?))
      {}   =fn=> (check! (valid?* ::grp.art/env))
      data =fn=> (check! x-double?)
      data =fn=> (check! failing-checker)
      data =fn=> (check!
                   (embeds?* {:a "A"})
                   (embeds?* {:b {:c "C"}}))
      )))
