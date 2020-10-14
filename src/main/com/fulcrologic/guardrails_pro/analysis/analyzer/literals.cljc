(ns com.fulcrologic.guardrails-pro.analysis.analyzer.literals
  (:require
    [com.fulcrologic.guardrails.core :as gr :refer [>defn => ?]]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :as grp.ana.disp]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [taoensso.timbre :as log]
    [taoensso.encore :as enc]))

(defn analyze-literal! [env sexpr]
  (log/spy :debug :analyze/literal
    (let [spec (cond
                 (number? sexpr) number?
                 (string? sexpr) string?
                 (keyword? sexpr) (do (when (and (qualified-keyword? sexpr)
                                              (not (grp.spec/lookup env sexpr)))
                                        (grp.art/record-warning! env sexpr
                                          :warning/qualified-keyword-missing-spec))
                                    keyword?)
                 (nil? sexpr) nil?
                 (char? sexpr) char?
                 (grp.ana.disp/regex? sexpr) grp.ana.disp/regex?)]
      {::grp.art/spec                spec
       ::grp.art/samples             #{sexpr}
       ::grp.art/original-expression sexpr})))

(defmethod grp.ana.disp/analyze-mm :literal/char [env sexpr] (analyze-literal! env sexpr))
(defmethod grp.ana.disp/analyze-mm :literal/number [env sexpr] (analyze-literal! env sexpr))
(defmethod grp.ana.disp/analyze-mm :literal/string [env sexpr] (analyze-literal! env sexpr))
(defmethod grp.ana.disp/analyze-mm :literal/keyword [env sexpr] (analyze-literal! env sexpr))
(defmethod grp.ana.disp/analyze-mm :literal/regex [env sexpr] (analyze-literal! env sexpr))
(defmethod grp.ana.disp/analyze-mm :literal/nil [env sexpr] (analyze-literal! env sexpr))

(>defn validate-samples! [env k v samples]
  [::grp.art/env any? any? ::grp.art/samples => (? ::grp.art/samples)]
  (let [spec (grp.spec/lookup env k)]
    (enc/if-let [spec           spec
                 failing-sample (some (fn _invalid-sample [sample]
                                        (when-not (grp.spec/valid? env spec sample) sample))
                                  samples)]
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
                           ::grp.art/Unknown)))]
    (assoc acc k sample-value)))

(>defn analyze-hashmap! [env hashmap]
  [::grp.art/env map? => ::grp.art/type-description]
  (let [sample-map (reduce-kv (partial analyze-hashmap-entry env) {} hashmap)]
    {::grp.art/samples             #{sample-map}
     ::grp.art/original-expression hashmap
     ::grp.art/type                "literal-hashmap"}))

(defmethod grp.ana.disp/analyze-mm :collection/map [env coll] (analyze-hashmap! env coll))

(defn analyze-vector-entry
  [env acc v]
  (let [sample (let [{::grp.art/keys [samples]} (grp.ana.disp/-analyze! env v)]
                 (when (seq samples) {:sample-value (rand-nth (vec samples))}))]
    (if (contains? sample :sample-value)
      (conj acc (:sample-value sample))
      (conj acc ::grp.art/Unknown))))

(>defn analyze-vector! [env v]
  [::grp.art/env vector? => ::grp.art/type-description]
  (let [sample-vector (reduce (partial analyze-vector-entry env) [] v)]
    {::grp.art/samples             #{sample-vector}
     ::grp.art/original-expression v
     ::grp.art/type                "literal-vector"}))

(defmethod grp.ana.disp/analyze-mm :collection/vector [env coll] (analyze-vector! env coll))

(defn cartesian-product
  "taken from clojure.math.combinatorics
   All the ways to take one item from each sequence"
  [& seqs]
  (let [v-original-seqs (vec seqs)
        step (fn step [v-seqs]
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
    {::grp.art/samples             samples
     ::grp.art/original-expression s
     ::grp.art/type                "literal-set"}))

(defmethod grp.ana.disp/analyze-mm :collection/set [env coll] (analyze-set! env coll))
