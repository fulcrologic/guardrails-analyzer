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
              (and (qualified-keyword? k) (= (name k) "keys"))
              #_=> (mapv (fn [sym]
                           (let [spec-kw (keyword (namespace k) (str sym))]
                             (if-let [spec (s/get-spec spec-kw)]
                               (let [samples (grp.sampler/try-sampling! env (s/gen spec) {::grp.art/original-expression entry})
                                     type    (cond-> {::grp.art/spec                spec
                                                      ::grp.art/original-expression (pr-str sym)
                                                      ::grp.art/type                (pr-str sym)}
                                               samples (assoc ::grp.art/samples (set samples)))]
                                 (grp.art/record-binding! env sym type)
                                 [sym type])
                               (grp.art/record-warning! env sym
                                 (str "Fully-qualified destructured symbol "
                                   sym
                                   " has no spec. This could indicate a spelling error or"
                                   " a missing spec.")))))
                     v)
              (qualified-keyword? v)
              #_=> (when-let [spec (s/get-spec v)]
                     (let [samples (grp.sampler/try-sampling! env (s/gen spec) {::grp.art/original-expression entry})
                           type    (cond-> {::grp.art/spec                spec
                                            ::grp.art/original-expression (pr-str k)
                                            ::grp.art/type                (pr-str v)}
                                     samples (assoc ::grp.art/samples (set samples)))]
                       (grp.art/record-binding! env k type)
                       [[k type]]))
              (= :as k)
              #_=> (let [typ (assoc value-type-desc ::grp.art/original-expression v)]
                     (grp.art/record-binding! env v typ)
                     [[v typ]])))]
    (let [typ (assoc value-type-desc ::grp.art/original-expression bind-sexpr)]
      (cond
        (symbol? bind-sexpr) (do
                               (grp.art/record-binding! env bind-sexpr typ)
                               {bind-sexpr typ})
        (vector? bind-sexpr) (let [as-sym (some #(when (= :as (first %))
                                                   (second %))
                                            (partition 2 1 bind-sexpr))]
                               (if as-sym
                                 (do
                                   (grp.art/record-binding! env as-sym typ)
                                   {as-sym typ})
                                 {}))
        ;; TODO: might need to return for :keys [x ...] an empty type desc
        (map? bind-sexpr) (into {}
                            (mapcat MAP*)
                            bind-sexpr)))))
