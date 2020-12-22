(ns com.fulcrologic.copilot.artifacts-spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.stateful.generators :as st.gen]
    [com.fulcrologic.copilot.test-checkers :as tc]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [com.fulcrologic.guardrails.registry :as gr.reg]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "fix-kw-nss"
  (assertions
    (#'cp.art/fix-kw-nss {::gr.reg/foo {::gr.reg/bar 1}
                           ::_/a ::_/b
                           :qux {:wub 2}})
    => {::cp.art/foo {::cp.art/bar 1}
        ::_/a ::_/b
        :qux {:wub 2}}))

(specification "resolve-quoted-specs"
  (let [spec-registry {'int? int?
                       'string? string?}
        test-env {::cp.art/spec-registry spec-registry
                  ::cp.art/externs-registry {}}]
    (assertions
      (#'cp.art/resolve-quoted-specs test-env
        {::cp.art/quoted.argument-specs '[int?]})
      => {::cp.art/quoted.argument-specs '[int?]
          ::cp.art/argument-specs [int?]}
      (#'cp.art/resolve-quoted-specs test-env
        {::cp.art/quoted.argument-specs '[int?]
         ::cp.art/quoted.return-spec 'string?})
      => {::cp.art/quoted.argument-specs '[int?]
          ::cp.art/argument-specs [int?]
          ::cp.art/quoted.return-spec 'string?
          ::cp.art/return-spec string?})))

(defn wrap-spec-gen [spec f]
  (s/with-gen spec
    (fn [] (f (s/gen spec)))))

(s/def ::arity (conj (set (range (inc 20))) :n))
(s/def ::specs (s/coll-of qualified-keyword? :kind vector?
                 :gen #(gen/let [arity (st.gen/get-value [:arity] 0)
                                 varargs? (st.gen/get-value [:varargs?] false)
                                 v (gen/vector gen/keyword-ns arity)
                                 kw gen/keyword-ns]
                         (cond-> v varargs? (conj kw)))))
(s/def ::arglist (s/coll-of simple-symbol? :kind vector?
                   :gen #(gen/let [arity (st.gen/get-value [:arity] 0)
                                   varargs? (st.gen/get-value [:varargs?] false)
                                   v (gen/vector gen/symbol arity)
                                   sym gen/symbol]
                           (cond-> v varargs? (conj '& sym)))))
(s/def ::arity-detail
  (wrap-spec-gen
    (s/keys :req [::arglist ::specs])
    (fn [g] (gen/let [arity (s/gen #{0 1 2 3 4})
                      varargs? (gen/frequency
                                 [[1 (gen/return true)]
                                  [3 (gen/return false)]])]
              (st.gen/with-default-state g
                {:arity arity
                 :varargs? varargs?})))))
(s/def ::arities
  (s/every-kv ::arity ::arity-detail
    :gen #(st.gen/stateful
            (gen/let [arities (gen/vector
                                (st.gen/unique :arities
                                  (s/gen #{0 1 2 3 4}))
                                1 4)
                      varargs? (gen/frequency
                                 [[1 (gen/return true)]
                                  [2 (gen/return false)]])]
              (let [kvs (mapcat
                          (fn [arity]
                            (let [varargs? (and varargs? (= arity (apply max arities)))]
                              [(if varargs? :n arity)
                               (st.gen/stateful
                                 (s/gen ::arity-detail)
                                 {:arity arity
                                  :varargs? varargs?})]))
                          arities)]
                (apply gen/hash-map kvs))))))

(def consistent:arity-detail?*
  (_/checker [value]
    (let [lists (map (partial remove #{'&})
                  (vals (select-keys value [::arglist ::specs])))]
      (when-not (apply = (map count lists))
        {:actual (map count lists)
         :expected `(= ~@lists)}))))

(def consistent:arities?*
  (_/checker [value]
    (for [[arity detail] value
          :let [arglist (::arglist detail)
                len (count arglist)]]
      [(when-not (or (= :n arity) (= arity len))
        {:actual arglist
         :expected arity})
       (when (and (= :n arity) (not (some #{'&} arglist)))
         {:actual arglist
          :expected `(some #{'&})})])))

(specification "stateful arities" :play
  (assertions
    (gen/sample (st.gen/stateful (s/gen ::arglist) {:arity 1}))
    =check=> (_/every?* (_/every?* (_/is?* symbol?)))
    (gen/sample (st.gen/stateful (s/gen ::specs) {:arity 1}))
    =check=> (_/every?* (_/every?* (_/is?* keyword?)))
    "varargs"
    (gen/sample (st.gen/stateful (s/gen ::arglist) {:arity 1 :varargs? true}))
    =check=> (_/every?* (_/every?* (_/is?* symbol?)))
    "with default state"
    (gen/sample (s/gen ::arity-detail))
    =check=> (_/every?* consistent:arity-detail?*)
    (gen/sample (st.gen/with-default-state (s/gen ::arglist) {:arity 1}))
    =check=> (_/every?* (tc/of-length?* 1))
    "arities"
    (gen/sample (s/gen ::arities))
    =check=> (_/every?*
               consistent:arities?*
               (tc/of-length?* 1 4))))
