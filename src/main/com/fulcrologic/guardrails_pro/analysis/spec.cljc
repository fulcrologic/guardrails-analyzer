(ns com.fulcrologic.guardrails-pro.analysis.spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]))

;; TODO: look in guardrails config for
;; - a dispatch keyword or ns sym
;; - use it to pick implementation
;; ? should it be dynamically loaded ?
;; ? should the impls be cached ? (at compile time ?)

(defn lookup [k]
  ;; malli: `schema`
  (s/get-spec k))

(defn valid? [spec value]
  ;; malli: `validate`
  (s/valid? spec value))

(defn explain-str [spec value]
  ;; malli: `explain` & `humanize`
  (s/explain-str spec value))

(defn generator [spec]
  ;; malli: `generator`
  (s/gen spec))

;; NOTE: what about custom generator options? (eg: size & seed)
(defn generate [spec]
  ;; malli: `generate`
  (gen/generate spec))

;; NOTE: what about custom generator options? (eg: size & seed)
(defn sample [spec]
  ;; malli: `sample`
  (gen/sample spec))
