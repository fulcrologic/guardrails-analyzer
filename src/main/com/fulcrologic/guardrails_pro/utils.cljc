(ns com.fulcrologic.guardrails-pro.utils
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [taoensso.timbre :as log])
  (:import (clojure.lang IMeta)))

(>defn ?meta [sexpr]
  [any? => (? map?)]
  (when (instance? IMeta sexpr)
    (meta sexpr)))

(>defn try-sampling [{::grp.art/keys [return-spec generator] :as sampler}]
  [::grp.art/spec => (? (s/coll-of any? :min-count 1))]
  (try
    (gen/sample
      (or generator (s/gen return-spec)))
    (catch #?(:clj Exception :cljs :default) e
      (log/info "Cannot sample from:" sampler)
      nil)))

(s/def ::destructurable
  (s/or
    :symbol symbol?
    :vector vector?
    :map map?))

(>defn destructure* [env bind-sexpr value-type-desc]
  [::grp.art/env ::destructurable ::grp.art/type-description
   => (s/map-of symbol? ::grp.art/type-description)]
  (log/info :destructure* bind-sexpr value-type-desc)
  (letfn [(MAP* [[k v]]
            (cond
              (qualified-keyword? v)
              #_=> (when-let [spec (s/get-spec v)]
                     (let [samples (try-sampling {::grp.art/return-spec spec :keyword v})]
                       [[k (cond-> {::grp.art/spec spec ::grp.art/type (pr-str v)}
                             samples (assoc ::grp.art/samples samples))]]))
              (and (qualified-keyword? k) (= (name k) "keys"))
              #_=> (map (fn [sym]
                          (let [spec-kw (keyword (namespace k) (str sym))]
                            (when-let [spec (s/get-spec spec-kw)]
                              (let [samples (try-sampling {::grp.art/return-spec spec
                                                           :keyword spec-kw})]
                                [sym (cond-> {::grp.art/spec spec ::grp.art/type (pr-str sym)}
                                       samples (assoc ::grp.art/samples samples))]))))
                     v)
              (= :as k)
              #_=> [[v value-type-desc]]))]
    (cond
      (symbol? bind-sexpr) {bind-sexpr value-type-desc}
      (vector? bind-sexpr) (let [as-sym (some #(when (= :as (first %))
                                                 (second %))
                                          (partition 2 1 bind-sexpr))]
                             (cond-> {}
                               as-sym (assoc as-sym value-type-desc)))
      ;; TODO: might need to return for :keys [x ...] an empty type desc
      (map? bind-sexpr)    (into {}
                             (mapcat MAP*)
                             bind-sexpr))))
