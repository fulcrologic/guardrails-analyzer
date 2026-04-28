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

(ns com.fulcrologic.guardrails-analyzer.forms)

;; TODO: would this be faster with spectre ?
(defn form-expression
  "Converts the given form into a runtime expression that will re-create the form (including metadata)."
  [form]
  (let [x (cond
            (symbol? form) `(quote ~form)
            (seq? form) (list* 'list (map form-expression form))
            (vector? form) (mapv form-expression form)
            (map? form) (reduce-kv
                         (fn [acc k v]
                           (assoc acc
                                  (form-expression k)
                                  (form-expression v)))
                         {} form)
            (set? form) (set (map form-expression form))
            :else form)]
    (if-let [m (meta form)]
      (list 'with-meta x m)
      x)))

(defn- preserve-meta [original rebuilt]
  (if-let [m (meta original)]
    (with-meta rebuilt m)
    rebuilt))

(defn interpret [x]
  (cond
    (and (seq? x) (= 'with-meta (first x)))
    #_=> (with-meta (interpret (second x)) (last x))
    (and (seq? x) (= 'list (first x)))
    #_=> (apply list (map interpret (rest x)))
    (and (seq? x) (= 'quote (first x)) (symbol? (second x)))
    #_=> (symbol (second x))
    (map? x) (preserve-meta x (apply hash-map (mapcat interpret x)))
    (vector? x) (preserve-meta x (apply vector (map interpret x)))
    (list? x) (preserve-meta x (apply list (map interpret x)))
    (set? x) (preserve-meta x (set (map interpret x)))
    :else x))
