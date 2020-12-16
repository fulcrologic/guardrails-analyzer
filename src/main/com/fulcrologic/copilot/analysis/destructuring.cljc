(ns com.fulcrologic.copilot.analysis.destructuring
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.analysis.spec :as cp.spec]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn >defn- =>]]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(s/def ::destructurable
  (s/or
    :symbol symbol?
    :vector vector?
    :map map?))

(declare -destructure!)

(>defn- ?validate-samples! [env kw samples & [orig-expr]]
  [::cp.art/env qualified-keyword? ::cp.art/samples
   (s/? ::cp.art/original-expression)
   => any?]
  (if-let [spec (cp.spec/lookup env kw)]
    (do
      (when-not (some #(and (associative? %) (contains? % kw)) samples)
        (cp.art/record-warning! env
          #::cp.art{:problem-type        :warning/failed-to-find-keyword-in-hashmap-samples
                    :expected            #::cp.art{:spec spec :type (pr-str kw)}
                    :actual              {::cp.art/failing-samples samples}
                    :original-expression (or orig-expr kw)}))
      (when-let [failing-case (some #(when-not (cp.spec/valid? env spec %) %)
                                (map kw samples))]
        (cp.art/record-error! env
          #::cp.art{:problem-type        :error/value-failed-spec
                    :expected            #::cp.art{:spec spec :type (pr-str kw)}
                    :actual              {::cp.art/failing-samples #{failing-case}}
                    :original-expression (or orig-expr kw)})))
    (cp.art/record-warning! env (or orig-expr kw)
      :warning/qualified-keyword-missing-spec)))

(>defn destr-map-entry! [env [k v] td]
  [::cp.art/env map-entry? ::cp.art/type-description
   => (s/coll-of (s/tuple symbol? ::cp.art/type-description))]
  (let [k-val (cp.art/unwrap-meta k)
        v-val (cp.art/unwrap-meta v)]
    (cond
      (= :keys k-val) {}
      (and (qualified-keyword? k-val) (= (name k-val) "keys"))
      #_=> (mapv (fn [sym]
                   (let [spec-kw (keyword (namespace k-val) (str sym))]
                     (?validate-samples! env spec-kw (::cp.art/samples td) sym)
                     [sym {::cp.art/original-expression sym
                           ::cp.art/samples             (set (map spec-kw (::cp.art/samples td)))
                           ::cp.art/spec                spec-kw
                           ::cp.art/type                (pr-str spec-kw)}]))
             v)
      (qualified-keyword? v-val)
      #_=> (do
             (?validate-samples! env v-val (::cp.art/samples td) v)
             (-destructure! env k
               {::cp.art/original-expression k
                ::cp.art/samples             (set (map v-val (::cp.art/samples td)))
                ::cp.art/spec                v-val
                ::cp.art/type                (pr-str v-val)}))
      (simple-keyword? v-val)
      #_=> (-destructure! env k
             #::cp.art{:original-expression k
                       :samples             (set (map v-val (::cp.art/samples td)))})
      (= :as k-val)
      #_=> [[v (assoc td ::cp.art/original-expression v)]]
      :else [])))

(>defn destr-vector! [env vect td]
  [::cp.art/env vector? ::cp.art/type-description
   => (s/coll-of (s/tuple symbol? ::cp.art/type-description))]
  (let [[symbols specials] (split-with (comp (complement #{'& :as}) cp.art/unwrap-meta) vect)
        coll-bindings (into {}
                        (map (fn [[expr bind]]
                               [bind
                                (case (cp.art/unwrap-meta expr)
                                  :as td
                                  '& {::cp.art/samples
                                      (set (map (partial drop (count symbols))
                                             (::cp.art/samples td)))})]))
                        (partition 2 specials))]
    (into coll-bindings
      (mapcat
        (fn [i sym]
          (-destructure! env sym
            {::cp.art/samples
             (set (map #(nth % i nil)
                    (::cp.art/samples td)))}))
        (range)
        symbols))))

(>defn -destructure! [env bind-sexpr value-type-desc]
  [::cp.art/env ::destructurable ::cp.art/type-description
   => (s/map-of symbol? ::cp.art/type-description)]
  (let [typ (assoc value-type-desc ::cp.art/original-expression bind-sexpr)]
    (cond
      (::cp.art/unknown-expression value-type-desc) {}
      (symbol? bind-sexpr) {bind-sexpr typ}
      (vector? bind-sexpr) (destr-vector! env bind-sexpr typ)
      (map? bind-sexpr)
      (into {}
        (mapcat #(destr-map-entry! env % value-type-desc))
        bind-sexpr))))

(>defn destructure! [env bind-sexpr value-type-desc]
  [::cp.art/env ::destructurable ::cp.art/type-description
   => (s/map-of symbol? ::cp.art/type-description)]
  (try
    (let [bindings (-destructure! env bind-sexpr value-type-desc)]
      (doseq [[sym td] bindings]
        (cp.art/record-binding! env sym td))
      bindings)
    (catch #?(:clj Exception :cljs :default) e
      (log/error e "Error destructuring expression:" bind-sexpr "with value:" value-type-desc)
      {})))
