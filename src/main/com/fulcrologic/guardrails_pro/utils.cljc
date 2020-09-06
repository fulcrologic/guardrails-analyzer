(ns com.fulcrologic.guardrails-pro.utils
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.sampler :as grp.sampler]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [taoensso.timbre :as log]))

(s/def ::destructurable
  (s/or
    :symbol symbol?
    :vector vector?
    :map map?))

;; TODO: check for errors => destructure!
(>defn destructure* [env bind-sexpr value-type-desc]
  [::grp.art/env ::destructurable ::grp.art/type-description
   => (s/map-of symbol? ::grp.art/type-description)]
  (log/info :destructure* bind-sexpr value-type-desc)
  (letfn [(MAP* [[k v :as entry]]
            (cond
              (qualified-keyword? v)
              #_=> (when-let [spec (s/get-spec v)]
                     (let [samples (grp.sampler/try-sampling! env (s/gen spec) {::grp.art/original-expression entry})]
                       [[k (cond-> {::grp.art/spec spec ::grp.art/type (pr-str v)}
                             samples (assoc ::grp.art/samples samples))]]))
              (and (qualified-keyword? k) (= (name k) "keys"))
              #_=> (map (fn [sym]
                          (let [spec-kw (keyword (namespace k) (str sym))]
                            (when-let [spec (s/get-spec spec-kw)]
                              (let [samples (grp.sampler/try-sampling! env (s/gen spec) {::grp.art/original-expression entry})]
                                [sym (cond-> {::grp.art/spec spec ::grp.art/type (pr-str sym)}
                                       samples (assoc ::grp.art/samples samples))]))))
                     v)
              (= :as k)
              #_=> [[v value-type-desc]]))]
    (cond
      (symbol? bind-sexpr) (log/spy :info {bind-sexpr value-type-desc})
      (vector? bind-sexpr) (let [as-sym (some #(when (= :as (first %))
                                                 (second %))
                                          (partition 2 1 bind-sexpr))]
                             (cond-> {}
                               as-sym (assoc as-sym value-type-desc)))
      ;; TODO: might need to return for :keys [x ...] an empty type desc
      (map? bind-sexpr)    (into {}
                             (mapcat MAP*)
                             bind-sexpr))))
