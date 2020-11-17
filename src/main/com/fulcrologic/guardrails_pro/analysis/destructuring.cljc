(ns com.fulcrologic.guardrails-pro.analysis.destructuring
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn >defn- =>]]))

(s/def ::destructurable
  (s/or
    :symbol symbol?
    :vector vector?
    :map map?))

(declare -destructure!)

(>defn- ?validate-samples! [env kw samples & [orig-expr]]
  [::grp.art/env qualified-keyword? ::grp.art/samples
   (s/? ::grp.art/original-expression)
   => any?]
  (if-let [spec (grp.spec/lookup env kw)]
    (do
      (when-not (some #(contains? % kw) samples)
        (grp.art/record-warning! env kw :warning/failed-to-find-keyword-in-hashmap-samples))
      (when-let [failing-case (some #(when-not (grp.spec/valid? env spec %) %)
                                (map kw samples))]
        (grp.art/record-error! env
          #::grp.art{:problem-type        :error/value-failed-spec
                     :expected            #::grp.art{:spec spec :type (pr-str kw)}
                     :actual              {::grp.art/failing-samples #{failing-case}}
                     :original-expression (or orig-expr kw)})))
    (grp.art/record-warning! env kw
      :warning/qualified-keyword-missing-spec)))

(>defn destr-map-entry! [env [k v] td]
  [::grp.art/env map-entry? ::grp.art/type-description
   => (s/coll-of (s/tuple symbol? ::grp.art/type-description))]
  (cond
    (= :keys k) {}
    (and (qualified-keyword? k) (= (name k) "keys"))
    #_=> (mapv (fn [sym]
                 (let [spec-kw (keyword (namespace k) (str sym))]
                   (?validate-samples! env spec-kw (::grp.art/samples td) sym)
                   [sym {::grp.art/original-expression sym
                         ::grp.art/samples             (set (map spec-kw (::grp.art/samples td)))
                         ::grp.art/spec                spec-kw
                         ::grp.art/type                (pr-str spec-kw)}]))
           v)
    (qualified-keyword? v)
    #_=> (do
           (?validate-samples! env v (::grp.art/samples td))
           (-destructure! env k
             {::grp.art/original-expression k
              ::grp.art/samples             (set (map v (::grp.art/samples td)))
              ::grp.art/spec                v
              ::grp.art/type                (pr-str v)}))
    (keyword? v)
    #_=> (-destructure! env k
           #::grp.art{:original-expression k
                      :samples             (set (map v (::grp.art/samples td)))})
    (= :as k)
    #_=> [[v (assoc td ::grp.art/original-expression v)]]
    :else []))

(>defn destr-vector! [env vect td]
  [::grp.art/env vector? ::grp.art/type-description
   => (s/coll-of (s/tuple symbol? ::grp.art/type-description))]
  (let [[symbols specials] (split-with (complement #{'& :as}) vect)
        sym-count     (count symbols)
        coll-bindings (into {}
                        (map (fn [[expr bind]]
                               [bind
                                (case expr
                                  :as td
                                  '& {::grp.art/samples
                                      (set (map (partial drop sym-count)
                                             (::grp.art/samples td)))})]))
                        (partition 2 specials))]
    (into coll-bindings
      (mapcat
        (fn [i sym]
          (-destructure! env sym
            {::grp.art/samples
             (set (map #(nth % i nil)
                    (::grp.art/samples td)))}))
        (range)
        symbols))))

(>defn -destructure! [env bind-sexpr value-type-desc]
  [::grp.art/env ::destructurable ::grp.art/type-description
   => (s/map-of symbol? ::grp.art/type-description)]
  (let [typ (assoc value-type-desc ::grp.art/original-expression bind-sexpr)]
    (cond
      (::grp.art/unknown-expression value-type-desc) {}
      (symbol? bind-sexpr) {bind-sexpr typ}
      (vector? bind-sexpr) (destr-vector! env bind-sexpr typ)
      (map? bind-sexpr)
      (into {}
        (mapcat #(destr-map-entry! env % value-type-desc))
        bind-sexpr))))

(>defn destructure! [env bind-sexpr value-type-desc]
  [::grp.art/env ::destructurable ::grp.art/type-description
   => (s/map-of symbol? ::grp.art/type-description)]
  (let [bindings (-destructure! env bind-sexpr value-type-desc)]
    (doseq [[sym td] bindings]
      (grp.art/record-binding! env sym td))
    bindings))
