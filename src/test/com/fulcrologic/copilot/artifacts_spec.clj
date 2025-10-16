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
    [fulcro-spec.core :refer [assertions specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "fix-kw-nss"
  (assertions
    (#'cp.art/fix-kw-nss {::gr.reg/foo {::gr.reg/bar 1}
                          ::_/a        ::_/b
                          :qux         {:wub 2}})
    => {::cp.art/foo {::cp.art/bar 1}
        ::_/a        ::_/b
        :qux         {:wub 2}}))

(specification "resolve-quoted-specs"
  (let [spec-registry {'int?    int?
                       'string? string?}
        test-env      {::cp.art/spec-registry    spec-registry
                       ::cp.art/externs-registry {}}]
    (assertions
      (#'cp.art/resolve-quoted-specs test-env
        {::cp.art/quoted.argument-specs '[int?]})
      => {::cp.art/quoted.argument-specs '[int?]
          ::cp.art/argument-specs        [int?]}
      (#'cp.art/resolve-quoted-specs test-env
        {::cp.art/quoted.argument-specs '[int?]
         ::cp.art/quoted.return-spec    'string?})
      => {::cp.art/quoted.argument-specs '[int?]
          ::cp.art/argument-specs        [int?]
          ::cp.art/quoted.return-spec    'string?
          ::cp.art/return-spec           string?})))

(defn wrap-spec-gen [spec f]
  (s/with-gen spec
    (fn [] (f (s/gen spec)))))

(s/def ::arity (conj (set (range (inc 20))) :n))
(s/def ::specs (s/coll-of qualified-keyword? :kind vector?
                 :gen #(gen/let [arity    (st.gen/get-value [:arity] 0)
                                 varargs? (st.gen/get-value [:varargs?] false)
                                 v        (gen/vector gen/keyword-ns arity)
                                 kw       gen/keyword-ns]
                         (cond-> v varargs? (conj kw)))))
(s/def ::arglist (s/coll-of simple-symbol? :kind vector?
                   :gen #(gen/let [arity    (st.gen/get-value [:arity] 0)
                                   varargs? (st.gen/get-value [:varargs?] false)
                                   v        (gen/vector gen/symbol arity)
                                   sym      gen/symbol]
                           (cond-> v varargs? (conj '& sym)))))
(s/def ::arity-detail
  (wrap-spec-gen
    (s/keys :req [::arglist ::specs])
    (fn [g] (gen/let [arity    (s/gen #{0 1 2 3 4})
                      varargs? (gen/frequency
                                 [[1 (gen/return true)]
                                  [3 (gen/return false)]])]
              (st.gen/with-default-state g
                {:arity    arity
                 :varargs? varargs?})))))
(s/def ::arities
  (s/every-kv ::arity ::arity-detail
    :gen #(st.gen/stateful
            (gen/let [arities  (gen/vector
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
                                 {:arity    arity
                                  :varargs? varargs?})]))
                          arities)]
                (apply gen/hash-map kvs))))))

(def consistent:arity-detail?*
  (_/checker [value]
    (let [lists (map (partial remove #{'&})
                  (vals (select-keys value [::arglist ::specs])))]
      (when-not (apply = (map count lists))
        {:actual   (map count lists)
         :expected `(= ~@lists)}))))

(def consistent:arities?*
  (_/checker [value]
    (for [[arity detail] value
          :let [arglist (::arglist detail)
                len     (count arglist)]]
      [(when-not (or (= :n arity) (= arity len))
         {:actual   arglist
          :expected arity})
       (when (and (= :n arity) (not (some #{'&} arglist)))
         {:actual   arglist
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

;; ========== PATH-BASED ANALYSIS TESTS ==========

(specification "Path condition spec"
  (assertions
    "Valid path condition with all required fields"
    (s/valid? ::cp.art/path-condition
      {::cp.art/condition-id         0
       ::cp.art/condition-expression '(even? x)
       ::cp.art/condition-location   {::cp.art/line-start 10 ::cp.art/column-start 5}
       ::cp.art/determined?          true
       ::cp.art/branch               :then})
    => true

    "Valid path condition with optional condition-value"
    (s/valid? ::cp.art/path-condition
      {::cp.art/condition-id         1
       ::cp.art/condition-expression '(pos? y)
       ::cp.art/condition-location   {::cp.art/line-start 12 ::cp.art/column-start 3}
       ::cp.art/determined?          true
       ::cp.art/branch               :else
       ::cp.art/condition-value      false})
    => true

    "Invalid without required fields"
    (s/valid? ::cp.art/path-condition
      {::cp.art/condition-id 0})
    => false))

(specification "Execution path spec"
  (assertions
    "Valid execution path with samples and bindings"
    (s/valid? ::cp.art/execution-path
      {::cp.art/path-id       0
       ::cp.art/conditions    [{::cp.art/condition-id         0
                                ::cp.art/condition-expression '(even? x)
                                ::cp.art/condition-location   {::cp.art/line-start 10 ::cp.art/column-start 5}
                                ::cp.art/determined?          true
                                ::cp.art/branch               :then}]
       ::cp.art/samples       #{4 8 12}
       ::cp.art/path-bindings {'x #{4 8 12}}})
    => true

    "Valid execution path with empty conditions (simple path)"
    (s/valid? ::cp.art/execution-path
      {::cp.art/path-id       0
       ::cp.art/conditions    []
       ::cp.art/samples       #{42}
       ::cp.art/path-bindings {}})
    => true

    "Valid merged path"
    (s/valid? ::cp.art/execution-path
      {::cp.art/path-id        0
       ::cp.art/conditions     []
       ::cp.art/samples        #{1 2 3}
       ::cp.art/path-bindings  {}
       ::cp.art/merged?        true
       ::cp.art/condition-sets [[{::cp.art/condition-id         0
                                  ::cp.art/condition-expression '(even? x)
                                  ::cp.art/condition-location   {::cp.art/line-start 10 ::cp.art/column-start 5}
                                  ::cp.art/determined?          true
                                  ::cp.art/branch               :then}]
                                [{::cp.art/condition-id         0
                                  ::cp.art/condition-expression '(even? x)
                                  ::cp.art/condition-location   {::cp.art/line-start 10 ::cp.art/column-start 5}
                                  ::cp.art/determined?          true
                                  ::cp.art/branch               :else}]]})
    => true

    "Invalid without required fields"
    (s/valid? ::cp.art/execution-path
      {::cp.art/path-id 0})
    => false))

(specification "Execution paths spec"
  (assertions
    "Valid execution paths (vector with at least one path)"
    (s/valid? ::cp.art/execution-paths
      [{::cp.art/path-id       0
        ::cp.art/conditions    []
        ::cp.art/samples       #{42}
        ::cp.art/path-bindings {}}])
    => true

    "Valid with multiple paths"
    (s/valid? ::cp.art/execution-paths
      [{::cp.art/path-id       0
        ::cp.art/conditions    [{::cp.art/condition-id         0
                                 ::cp.art/condition-expression '(even? x)
                                 ::cp.art/condition-location   {::cp.art/line-start 10 ::cp.art/column-start 5}
                                 ::cp.art/determined?          true
                                 ::cp.art/branch               :then}]
        ::cp.art/samples       #{4 8}
        ::cp.art/path-bindings {'x #{4 8}}}
       {::cp.art/path-id       1
        ::cp.art/conditions    [{::cp.art/condition-id         0
                                 ::cp.art/condition-expression '(even? x)
                                 ::cp.art/condition-location   {::cp.art/line-start 10 ::cp.art/column-start 5}
                                 ::cp.art/determined?          true
                                 ::cp.art/branch               :else}]
        ::cp.art/samples       #{1 5 9}
        ::cp.art/path-bindings {'x #{1 5 9}}}])
    => true

    "Invalid: empty vector not allowed"
    (s/valid? ::cp.art/execution-paths [])
    => false

    "Invalid: must be a vector"
    (s/valid? ::cp.art/execution-paths
      #{{::cp.art/path-id       0
         ::cp.art/conditions    []
         ::cp.art/samples       #{42}
         ::cp.art/path-bindings {}}})
    => false))

(specification "Type description with path-based option"
  (assertions
    "Valid path-based type description"
    (s/valid? ::cp.art/type-description
      {::cp.art/execution-paths
       [{::cp.art/path-id       0
         ::cp.art/conditions    []
         ::cp.art/samples       #{42}
         ::cp.art/path-bindings {}}]})
    => true

    "Valid path-based with multiple paths"
    (s/valid? ::cp.art/type-description
      {::cp.art/execution-paths
       [{::cp.art/path-id       0
         ::cp.art/conditions    [{::cp.art/condition-id         0
                                  ::cp.art/condition-expression '(even? x)
                                  ::cp.art/condition-location   {::cp.art/line-start 10 ::cp.art/column-start 5}
                                  ::cp.art/determined?          true
                                  ::cp.art/branch               :then}]
         ::cp.art/samples       #{4 8}
         ::cp.art/path-bindings {'x #{4 8}}}
        {::cp.art/path-id       1
         ::cp.art/conditions    [{::cp.art/condition-id         0
                                  ::cp.art/condition-expression '(even? x)
                                  ::cp.art/condition-location   {::cp.art/line-start 10 ::cp.art/column-start 5}
                                  ::cp.art/determined?          true
                                  ::cp.art/branch               :else}]
         ::cp.art/samples       #{1 5 9}
         ::cp.art/path-bindings {'x #{1 5 9}}}]})
    => true

    "Still valid with old-style samples (backwards compatibility)"
    (s/valid? ::cp.art/type-description
      {::cp.art/samples #{1 2 3}
       ::cp.art/spec    int?})
    => true

    "Path-based conforms as :path-based"
    (first (s/conform ::cp.art/type-description
             {::cp.art/execution-paths
              [{::cp.art/path-id       0
                ::cp.art/conditions    []
                ::cp.art/samples       #{42}
                ::cp.art/path-bindings {}}]}))
    => :path-based))

;; ========== PATH-BASED HELPER FUNCTIONS TESTS ==========

(specification "path-based? helper"
  (assertions
    "Returns true for path-based type description"
    (cp.art/path-based?
      {::cp.art/execution-paths
       [{::cp.art/path-id       0
         ::cp.art/conditions    []
         ::cp.art/samples       #{42}
         ::cp.art/path-bindings {}}]})
    => true

    "Returns false for old-style samples"
    (cp.art/path-based?
      {::cp.art/samples #{1 2 3}
       ::cp.art/spec    int?})
    => false

    "Returns false for unknown type"
    (cp.art/path-based?
      {::cp.art/unknown-expression '(some-expr)})
    => false))

(specification "ensure-path-based helper"
  (assertions
    "Returns path-based as-is"
    (cp.art/ensure-path-based
      {::cp.art/execution-paths
       [{::cp.art/path-id       0
         ::cp.art/conditions    []
         ::cp.art/samples       #{42}
         ::cp.art/path-bindings {}}]})
    => {::cp.art/execution-paths
        [{::cp.art/path-id       0
          ::cp.art/conditions    []
          ::cp.art/samples       #{42}
          ::cp.art/path-bindings {}}]}

    "Converts old-style samples to path-based"
    (cp.art/ensure-path-based
      {::cp.art/samples #{1 2 3}
       ::cp.art/spec    int?})
    => {::cp.art/samples #{1 2 3}
        ::cp.art/spec    int?
        ::cp.art/execution-paths
        [{::cp.art/path-id       0
          ::cp.art/conditions    []
          ::cp.art/samples       #{1 2 3}
          ::cp.art/path-bindings {}}]}

    "Returns unchanged when no samples"
    (cp.art/ensure-path-based
      {::cp.art/spec int?})
    => {::cp.art/spec int?}))

(specification "extract-all-samples helper"
  (assertions
    "Extracts samples from single path"
    (cp.art/extract-all-samples
      {::cp.art/execution-paths
       [{::cp.art/path-id       0
         ::cp.art/conditions    []
         ::cp.art/samples       #{1 2 3}
         ::cp.art/path-bindings {}}]})
    => #{1 2 3}

    "Unions samples from multiple paths"
    (cp.art/extract-all-samples
      {::cp.art/execution-paths
       [{::cp.art/path-id       0
         ::cp.art/conditions    []
         ::cp.art/samples       #{1 2}
         ::cp.art/path-bindings {}}
        {::cp.art/path-id       1
         ::cp.art/conditions    []
         ::cp.art/samples       #{3 4}
         ::cp.art/path-bindings {}}]})
    => #{1 2 3 4}

    "Returns old-style samples for non-path-based"
    (cp.art/extract-all-samples
      {::cp.art/samples #{10 20 30}})
    => #{10 20 30}

    "Returns empty set when no samples"
    (cp.art/extract-all-samples
      {::cp.art/spec int?})
    => #{}))

(specification "create-single-path helper"
  (assertions
    "Creates valid execution path"
    (cp.art/create-single-path #{42} {'x #{42}})
    => {::cp.art/path-id       0
        ::cp.art/conditions    []
        ::cp.art/samples       #{42}
        ::cp.art/path-bindings {'x #{42}}}

    "Creates path with empty bindings"
    (cp.art/create-single-path #{1 2 3} {})
    => {::cp.art/path-id       0
        ::cp.art/conditions    []
        ::cp.art/samples       #{1 2 3}
        ::cp.art/path-bindings {}}))

;; ============================================================================
;; Sample Partitioning and Path Management Tests
;; ============================================================================

(specification "resolve-pure-function"
  (let [test-env (cp.art/build-env {:NS "test.ns" :file "test.clj"})]
    (assertions
      "Returns nil for unknown function"
      (cp.art/resolve-pure-function test-env 'unknown-fn)
      => nil

      "Returns nil for non-pure function"
      (cp.art/resolve-pure-function test-env 'println)
      => nil)))

(specification "eval-condition - literals and basic operations"
  (let [test-env (cp.art/build-env {:NS "test.ns" :file "test.clj"})]
    (assertions
      "Evaluates literal true"
      (cp.art/eval-condition test-env true {})
      => {:result true :error? false}

      "Evaluates literal false"
      (cp.art/eval-condition test-env false {})
      => {:result false :error? false}

      "Evaluates numbers"
      (cp.art/eval-condition test-env 42 {})
      => {:result true :error? false}

      "Evaluates zero as falsey"
      (cp.art/eval-condition test-env 0 {})
      => {:result false :error? false}

      "Evaluates nil as falsey"
      (cp.art/eval-condition test-env nil {})
      => {:result false :error? false}

      "Resolves symbol from bindings"
      (cp.art/eval-condition test-env 'x {'x 42})
      => {:result true :error? false}

      "Resolves falsey value from bindings"
      (cp.art/eval-condition test-env 'x {'x false})
      => {:result false :error? false})))

(specification "eval-condition - collections"
  (let [test-env (cp.art/build-env {:NS "test.ns" :file "test.clj"})]
    (assertions
      "Evaluates vectors"
      (cp.art/eval-condition test-env [1 2 3] {})
      => {:result true :error? false}

      "Evaluates vectors with bindings"
      (cp.art/eval-condition test-env ['x 'y] {'x 1 'y 2})
      => {:result true :error? false}

      "Evaluates maps"
      (cp.art/eval-condition test-env {:a 1 :b 2} {})
      => {:result true :error? false}

      "Evaluates sets"
      (cp.art/eval-condition test-env #{1 2 3} {})
      => {:result true :error? false})))

(specification "eval-condition - built-in comparisons"
  (let [test-env (cp.art/build-env {:NS "test.ns" :file "test.clj"})]
    (assertions
      "Evaluates equality"
      (cp.art/eval-condition test-env '(= x 4) {'x 4})
      => {:result true :error? false}

      "Evaluates inequality"
      (cp.art/eval-condition test-env '(= x 5) {'x 4})
      => {:result false :error? false}

      "Evaluates less than"
      (cp.art/eval-condition test-env '(< x 10) {'x 4})
      => {:result true :error? false}

      "Evaluates greater than"
      (cp.art/eval-condition test-env '(> x 10) {'x 4})
      => {:result false :error? false}

      "Evaluates even?"
      (cp.art/eval-condition test-env '(even? x) {'x 4})
      => {:result true :error? false}

      "Evaluates odd?"
      (cp.art/eval-condition test-env '(odd? x) {'x 4})
      => {:result false :error? false})))

(specification "eval-condition - error handling"
  (let [test-env (cp.art/build-env {:NS "test.ns" :file "test.clj"})]
    (assertions
      "Returns error for unresolved symbol"
      (:error? (cp.art/eval-condition test-env 'unknown-symbol {}))
      => true

      "Returns error for unresolved function"
      (:error? (cp.art/eval-condition test-env '(unknown-fn x) {'x 42}))
      => true

      "Error result is false by default"
      (:result (cp.art/eval-condition test-env 'unknown-symbol {}))
      => false)))

(specification "partition-samples-by-condition"
  (let [test-env (cp.art/build-env {:NS "test.ns" :file "test.clj"})]
    (assertions
      "Partitions by even?"
      (cp.art/partition-samples-by-condition
        test-env
        '(even? x)
        'x
        #{1 2 3 4 5 6})
      => {:true-samples         #{2 4 6}
          :false-samples        #{1 3 5}
          :undetermined-samples #{}
          :determined?          true}

      "Partitions by equality"
      (cp.art/partition-samples-by-condition
        test-env
        '(= x 5)
        'x
        #{1 2 3 4 5 6})
      => {:true-samples         #{5}
          :false-samples        #{1 2 3 4 6}
          :undetermined-samples #{}
          :determined?          true}

      "Partitions by comparison"
      (cp.art/partition-samples-by-condition
        test-env
        '(> x 3)
        'x
        #{1 2 3 4 5})
      => {:true-samples         #{4 5}
          :false-samples        #{1 2 3}
          :undetermined-samples #{}
          :determined?          true}

      "Handles empty samples"
      (cp.art/partition-samples-by-condition
        test-env
        '(even? x)
        'x
        #{})
      => {:true-samples         #{}
          :false-samples        #{}
          :undetermined-samples #{}
          :determined?          true})))

(specification "limit-samples"
  (assertions
    "Returns samples unchanged when under limit"
    (cp.art/limit-samples #{1 2 3} 10)
    => #{1 2 3}

    "Returns samples unchanged when at limit"
    (cp.art/limit-samples #{1 2 3} 3)
    => #{1 2 3}

    "Limits samples when over limit"
    (count (cp.art/limit-samples #{1 2 3 4 5 6 7 8 9 10} 5))
    => 5

    "Handles empty samples"
    (cp.art/limit-samples #{} 10)
    => #{}))

(specification "deduplicate-paths"
  (assertions
    "Returns single path unchanged"
    (cp.art/deduplicate-paths
      [{::cp.art/path-id       0
        ::cp.art/conditions    []
        ::cp.art/samples       #{1 2 3}
        ::cp.art/path-bindings {'x #{1 2 3}}}])
    => [{::cp.art/path-id       0
         ::cp.art/conditions    []
         ::cp.art/samples       #{1 2 3}
         ::cp.art/path-bindings {'x #{1 2 3}}}]

    "Merges paths with identical samples"
    (cp.art/deduplicate-paths
      [{::cp.art/path-id       0
        ::cp.art/conditions    [{::cp.art/condition-id         0
                                 ::cp.art/condition-expression '(even? x)
                                 ::cp.art/determined?          true
                                 ::cp.art/branch               :then}]
        ::cp.art/samples       #{2 4}
        ::cp.art/path-bindings {'x #{2 4}}}
       {::cp.art/path-id       1
        ::cp.art/conditions    [{::cp.art/condition-id         1
                                 ::cp.art/condition-expression '(> x 3)
                                 ::cp.art/determined?          true
                                 ::cp.art/branch               :then}]
        ::cp.art/samples       #{2 4}
        ::cp.art/path-bindings {'y #{2 4}}}])
    =fn=> (fn [result]
            (and (= 1 (count result))
              (= #{2 4} (::cp.art/samples (first result)))
              (::cp.art/merged? (first result))
              (= 2 (count (::cp.art/condition-sets (first result))))))

    "Keeps paths with different samples separate"
    (count (cp.art/deduplicate-paths
             [{::cp.art/path-id       0
               ::cp.art/conditions    []
               ::cp.art/samples       #{1 2}
               ::cp.art/path-bindings {}}
              {::cp.art/path-id       1
               ::cp.art/conditions    []
               ::cp.art/samples       #{3 4}
               ::cp.art/path-bindings {}}]))
    => 2))

(specification "limit-paths"
  (assertions
    "Returns paths unchanged when under limit"
    (count (cp.art/limit-paths
             [{::cp.art/path-id       0
               ::cp.art/conditions    []
               ::cp.art/samples       #{1}
               ::cp.art/path-bindings {}}
              {::cp.art/path-id       1
               ::cp.art/conditions    []
               ::cp.art/samples       #{2}
               ::cp.art/path-bindings {}}]
             10))
    => 2

    "Limits paths when over limit"
    (count (cp.art/limit-paths
             (vec (for [i (range 100)]
                    {::cp.art/path-id       i
                     ::cp.art/conditions    []
                     ::cp.art/samples       #{i}
                     ::cp.art/path-bindings {}}))
             50))
    => 50

    "Returns empty for empty input"
    (cp.art/limit-paths [] 10)
    => []))

(specification "apply-path-limits"
  (assertions
    "Applies both deduplication and limits"
    (binding [cp.art/*max-paths*            5
              cp.art/*max-samples-per-path* 3]
      (let [paths  (vec (for [i (range 10)]
                          {::cp.art/path-id       i
                           ::cp.art/conditions    []
                           ::cp.art/samples       (set (range (* i 10) (+ (* i 10) 5)))
                           ::cp.art/path-bindings {}}))
            result (cp.art/apply-path-limits paths)]
        (and (<= (count result) 5)
          (every? #(<= (count (::cp.art/samples %)) 3) result))))
    => true

    "Handles empty paths"
    (cp.art/apply-path-limits [])
    => []))
