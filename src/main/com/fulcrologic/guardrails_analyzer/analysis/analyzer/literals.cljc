;; Copyright (c) Fulcrologic, LLC. All rights reserved.
;;
;; Permission to use this software requires that you
;; agree to our End-user License Agreement, legally obtain a license,
;; and use this software within the constraints of the terms specified
;; by said license.
;;
;; You may NOT publish, redistribute, or reproduce this software or its source
;; code in any form (printed, electronic, or otherwise) except as explicitly
;; allowed by your license agreement..

(ns com.fulcrologic.guardrails-analyzer.analysis.analyzer.literals
  (:require
    [com.fulcrologic.guardrails.core :as gr :refer [>defn => ?]]
    [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
    [com.fulcrologic.guardrails-analyzer.analysis.analyzer.dispatch :as cp.ana.disp]
    [com.fulcrologic.guardrails-analyzer.analysis.spec :as cp.spec])
  #?(:clj (:import (java.util.regex Pattern))))

(defn regex? [x]
  #?(:clj  (= (type x) Pattern)
     :cljs (regexp? x)))

(def kind->td
  {::nil           #::cp.art{:spec nil? :type "literal-nil"}
   ::char          #::cp.art{:spec char? :type "literal-char"}
   ::string        #::cp.art{:spec string? :type "literal-string"}
   ::regex         #::cp.art{:spec regex? :type "literal-regex"}
   ::number        #::cp.art{:spec number? :type "literal-number"}
   ::keyword       #::cp.art{:spec keyword? :type "literal-keyword"}
   ;; boolean? doesn't have a spec generator, so we omit :spec
   ::boolean       #::cp.art{:type "literal-boolean"}
   ::map           #::cp.art{:spec map? :type "literal-map"}
   ::vector        #::cp.art{:spec vector? :type "literal-vector"}
   ::set           #::cp.art{:spec set? :type "literal-set"}
   ::quoted-symbol #::cp.art{:type "quoted-symbol"}
   ::quoted-expr   #::cp.art{:type "quoted-expression"}})

(defn literal-td [env kind sexpr & [orig]]
  (assoc (kind->td kind)
    ::kind kind
    ::cp.art/samples #{sexpr}
    ::cp.art/original-expression (or orig sexpr)))

(defmethod cp.ana.disp/analyze-mm :literal/wrapped
  [env {:as orig :keys [kind value]}]
  (when (and (qualified-keyword? value)
          (not (cp.spec/lookup env value)))
    (cp.art/record-warning! env value :warning/qualified-keyword-missing-spec))
  (let [lit-kind (if-not (namespace kind)
                   (keyword (namespace ::_) (name kind))
                   kind)]
    (literal-td env lit-kind value orig)))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/quote [env [_ sexpr]]
  (if (symbol? sexpr)
    (literal-td env ::quoted-symbol sexpr)
    (literal-td env ::quoted-expr sexpr)))

(defn coll-td [env kind sexpr samples]
  (assoc (kind->td kind)
    ::kind kind
    ::cp.art/samples samples
    ::cp.art/original-expression sexpr))

(>defn validate-samples! [env k v samples]
  [::cp.art/env any? any? ::cp.art/samples => (? ::cp.art/samples)]
  (let [spec (cp.spec/lookup env k)]
    (if-let [failing-sample (and spec
                              (some (fn _invalid-sample [sample]
                                      (when-not (cp.spec/valid? env spec sample) sample))
                                samples))]
      (do
        (cp.art/record-error! env
          {::cp.art/original-expression v
           ::cp.art/expected            {::cp.art/spec spec ::cp.art/type (pr-str k)}
           ::cp.art/actual              {::cp.art/failing-samples #{failing-sample}}
           ::cp.art/problem-type        :error/value-failed-spec})
        samples)
      (when-let [valid-samples (and spec (seq (filter (partial cp.spec/valid? env spec) samples)))]
        (set valid-samples)))))

(defn analyze-hashmap-entry
  [env acc map-key v]
  (let [{::cp.art/keys [samples]} (cp.ana.disp/-analyze! env map-key)]
    (assert (and (first samples) (not (next samples))) "WIP: NOT IMPLEMENTED YET")
    (let [k (first samples)]
      (when (and (qualified-keyword? k) (nil? (cp.spec/lookup env k)))
        (cp.art/record-warning! env k :warning/qualified-keyword-missing-spec))
      (let [sample-value (let [{::cp.art/keys [samples]} (cp.ana.disp/-analyze! env v)]
                           (validate-samples! env k v samples)
                           (if (seq samples)
                             (rand-nth (vec samples))
                             (do
                               (cp.art/record-warning! env v :warning/missing-samples)
                               ::cp.art/unknown)))]
        (assoc acc k sample-value)))))

(>defn analyze-hashmap! [env hashmap]
  [::cp.art/env map? => ::cp.art/type-description]
  (let [sample-map (reduce-kv (partial analyze-hashmap-entry env) {} hashmap)]
    (coll-td env ::map hashmap #{sample-map})))

(defmethod cp.ana.disp/analyze-mm :collection/map [env coll] (analyze-hashmap! env coll))

(defn analyze-vector-entry
  [env acc v]
  (let [sample (let [{::cp.art/keys [samples]} (cp.ana.disp/-analyze! env v)]
                 (when (seq samples) {:sample-value (rand-nth (vec samples))}))]
    (if (contains? sample :sample-value)
      (conj acc (:sample-value sample))
      (conj acc ::cp.art/unknown))))

(>defn analyze-vector! [env v]
  [::cp.art/env vector? => ::cp.art/type-description]
  (let [sample-vector (reduce (partial analyze-vector-entry env) [] v)]
    (coll-td env ::vector v #{sample-vector})))

(defmethod cp.ana.disp/analyze-mm :collection/vector [env coll] (analyze-vector! env coll))

(defn cartesian-product
  "taken from clojure.math.combinatorics
   All the ways to take one item from each sequence"
  [& seqs]
  (let [v-original-seqs (vec seqs)
        step            (fn step [v-seqs]
                          (let [increment
                                (fn [v-seqs]
                                  (loop [i (dec (count v-seqs)), v-seqs v-seqs]
                                    (if (= i -1) nil
                                                 (if-let [rst (next (v-seqs i))]
                                                   (assoc v-seqs i rst)
                                                   (recur (dec i) (assoc v-seqs i (v-original-seqs i)))))))]
                            (when v-seqs
                              (cons (map first v-seqs)
                                (lazy-seq (step (increment v-seqs)))))))]
    (when (every? seq seqs)
      (lazy-seq (step v-original-seqs)))))

(>defn analyze-set! [env s]
  [::cp.art/env set? => ::cp.art/type-description]
  (let [samples (->> s
                  (map (comp ::cp.art/samples (partial cp.ana.disp/-analyze! env)))
                  (apply cartesian-product)
                  (map set)
                  set)]
    (coll-td env ::set s samples)))

(defmethod cp.ana.disp/analyze-mm :collection/set [env coll] (analyze-set! env coll))

(defmethod cp.ana.disp/analyze-mm :literal/boolean [env sexpr]
  (literal-td env ::boolean sexpr))
