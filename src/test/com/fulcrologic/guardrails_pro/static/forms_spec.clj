(ns com.fulcrologic.guardrails-pro.static.forms-spec
  (:require
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :refer [for-all]]
    [com.fulcrologic.guardrails-pro.static.forms :as forms]
    [com.rpl.specter :as sp])
  (:import
    [clojure.lang IMeta]))

(defn imeta? [x]
  (instance? IMeta x))

(defn recursively-add-meta [x]
  (sp/transform (sp/codewalker imeta?)
    #(if (< 0.5 (rand)) %
       (with-meta % (gen/generate (gen/map (gen/return "meta-key") (gen/return "meta-val")))))
    x))

(def any+meta
  (gen/fmap recursively-add-meta gen/any))

(defn interpret [x]
  (cond
    (and (seq? x) (= `with-meta (first x)))
    #_=> (with-meta (interpret (second x)) (last x))
    (and (seq? x) (= `list (first x)))
    #_=> (apply list (map interpret (rest x)))
    (and (seq? x) (= `quote (first x)) (symbol? (second x)))
    #_=> (symbol (second x))
    (map? x)    (apply hash-map (mapcat interpret x))
    (vector? x) (apply vector (map interpret x))
    (list? x)   (apply list (map interpret x))
    :else x))

(comment
  (defspec can-interpret-form-expression
    (for-all [x any+meta]
      (let [q (forms/form-expression x)
            i (interpret q)]
        (= i x)))))

(comment
  ;; NOTE: fails on ##NaN
  (defspec preserves-metadata-recursively {:num-times 500 :max-size 30}
    (for-all [x any+meta]
      (let [q  (forms/form-expression x)
            x' (sp/select [(sp/codewalker imeta?) (sp/view (juxt identity meta))] x)
            q' (sp/select [(sp/codewalker imeta?) (sp/view (juxt identity meta))] (interpret q))]
        (= q' x')))))
