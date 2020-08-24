(ns com.fulcrologic.guardrails-pro.utils
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [taoensso.timbre :as log]))

(>defn try-sampling [{::grp.art/keys [return-spec generator]}]
  [::grp.art/spec => (? (s/coll-of any? :min-count 1))]
  (try
    (gen/sample
      (or generator (s/gen return-spec)))
    (catch #?(:clj Exception :cljs :default) _
      (log/info "Cannot sample from:" (or generator return-spec))
      nil)))

(s/def ::destructurable
  (s/or
    :symbol symbol?
    :vector vector?
    :map    map?))

(>defn destructure* [env bind-sexpr value-type-desc]
  [::grp.art/env ::destructurable ::grp.art/type-description
   => (s/map-of symbol? ::grp.art/type-description)]
  (letfn [(MAP* [[k v]]
            (cond
              (and (keyword? v) (namespace v))
              #_=> (when-let [spec (s/get-spec v)]
                     (let [samples (try-sampling {::grp.art/return-spec spec})]
                       [[k (cond-> {::grp.art/spec spec}
                             samples (assoc ::grp.art/samples samples))]]))
              (and (keyword? k)
                (= (name k) "keys")
                (namespace k))
              #_=> (map (fn [sym]
                          (when-let [spec (s/get-spec
                                            (cond->> (str sym)
                                              (namespace k)
                                              (keyword (namespace k))))]
                            (let [samples (try-sampling {::grp.art/return-spec spec})]
                              [sym (cond-> {::grp.art/spec spec}
                                     samples (assoc ::grp.art/samples samples))])))
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
      (map? bind-sexpr)    (into {}
                             (mapcat MAP*)
                             bind-sexpr))))
