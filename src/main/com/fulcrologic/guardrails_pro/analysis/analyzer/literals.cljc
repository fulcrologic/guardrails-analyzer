(ns com.fulcrologic.guardrails-pro.analysis.analyzer.literals
  (:require
    [com.fulcrologic.guardrails.core :as gr :refer [>defn => ?]]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :as grp.ana.disp :refer [regex?]]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]))

(def kind->td
  {::nil           #::grp.art{:spec nil? :type "literal-nil"}
   ::char          #::grp.art{:spec char? :type "literal-char"}
   ::string        #::grp.art{:spec string? :type "literal-string"}
   ::regex         #::grp.art{:spec regex? :type "literal-regex"}
   ::number        #::grp.art{:spec number? :type "literal-number"}
   ::keyword       #::grp.art{:spec keyword? :type "literal-keyword"}
   ::map           #::grp.art{:spec map? :type "literal-map"}
   ::vector        #::grp.art{:spec vector? :type "literal-vector"}
   ::set           #::grp.art{:spec set? :type "literal-set"}
   ::quoted-symbol #::grp.art{:type "quoted-symbol"}
   ::quoted-expr   #::grp.art{:type "quoted-expression"}})

(defn literal-td [env kind sexpr]
  (assoc (kind->td kind)
    ::kind kind
    ::grp.art/samples #{sexpr}
    ::grp.art/original-expression sexpr))

(defmethod grp.ana.disp/analyze-mm :literal/nil [env sexpr] (literal-td env ::nil sexpr))
(defmethod grp.ana.disp/analyze-mm :literal/char [env sexpr] (literal-td env ::char sexpr))
(defmethod grp.ana.disp/analyze-mm :literal/string [env sexpr] (literal-td env ::string sexpr))
(defmethod grp.ana.disp/analyze-mm :literal/regex [env sexpr] (literal-td env ::regex sexpr))
(defmethod grp.ana.disp/analyze-mm :literal/number [env sexpr] (literal-td env ::number sexpr))
(defmethod grp.ana.disp/analyze-mm :literal/keyword [env sexpr]
  (when (and (qualified-keyword? sexpr)
          (not (grp.spec/lookup env sexpr)))
    (grp.art/record-warning! env sexpr :warning/qualified-keyword-missing-spec))
  (literal-td env ::keyword sexpr))

(defmethod grp.ana.disp/analyze-mm 'quote [env [_ sexpr]]
  (if (symbol? sexpr)
    (literal-td env ::quoted-symbol sexpr)
    (literal-td env ::quoted-expr sexpr)))

(defn coll-td [env kind sexpr samples]
  (assoc (kind->td kind)
    ::kind kind
    ::grp.art/samples samples
    ::grp.art/original-expression sexpr))

(>defn validate-samples! [env k v samples]
  [::grp.art/env any? any? ::grp.art/samples => (? ::grp.art/samples)]
  (let [spec (grp.spec/lookup env k)]
    (if-let [failing-sample (and spec
                              (some (fn _invalid-sample [sample]
                                      (when-not (grp.spec/valid? env spec sample) sample))
                                samples))]
      (do
        (grp.art/record-error! env
          {::grp.art/original-expression v
           ::grp.art/expected            {::grp.art/spec spec ::grp.art/type (pr-str k)}
           ::grp.art/actual              {::grp.art/failing-samples #{failing-sample}}
           ::grp.art/problem-type        :error/value-failed-spec})
        samples)
      (when-let [valid-samples (and spec (seq (filter (partial grp.spec/valid? env spec) samples)))]
        (set valid-samples)))))

(defn analyze-hashmap-entry
  [env acc k v]
  (when (and (qualified-keyword? k) (nil? (grp.spec/lookup env k)))
    (grp.art/record-warning! env k :warning/qualified-keyword-missing-spec))
  (let [sample-value (let [{::grp.art/keys [samples]} (grp.ana.disp/-analyze! env v)]
                       (validate-samples! env k v samples)
                       (if (seq samples)
                         (rand-nth (vec samples))
                         (do
                           (grp.art/record-warning! env v :warning/missing-samples)
                           ::grp.art/unknown)))]
    (assoc acc k sample-value)))

(>defn analyze-hashmap! [env hashmap]
  [::grp.art/env map? => ::grp.art/type-description]
  (let [sample-map (reduce-kv (partial analyze-hashmap-entry env) {} hashmap)]
    (coll-td env ::map hashmap #{sample-map})))

(defmethod grp.ana.disp/analyze-mm :collection/map [env coll] (analyze-hashmap! env coll))

(defn analyze-vector-entry
  [env acc v]
  (let [sample (let [{::grp.art/keys [samples]} (grp.ana.disp/-analyze! env v)]
                 (when (seq samples) {:sample-value (rand-nth (vec samples))}))]
    (if (contains? sample :sample-value)
      (conj acc (:sample-value sample))
      (conj acc ::grp.art/unknown))))

(>defn analyze-vector! [env v]
  [::grp.art/env vector? => ::grp.art/type-description]
  (let [sample-vector (reduce (partial analyze-vector-entry env) [] v)]
    (coll-td env ::vector v #{sample-vector})))

(defmethod grp.ana.disp/analyze-mm :collection/vector [env coll] (analyze-vector! env coll))

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
  [::grp.art/env set? => ::grp.art/type-description]
  (let [samples (->> s
                  (map (comp ::grp.art/samples (partial grp.ana.disp/-analyze! env)))
                  (apply cartesian-product)
                  (map set)
                  set)]
    (coll-td env ::set s samples)))

(defmethod grp.ana.disp/analyze-mm :collection/set [env coll] (analyze-set! env coll))
