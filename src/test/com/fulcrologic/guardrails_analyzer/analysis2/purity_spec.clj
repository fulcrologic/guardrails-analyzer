(ns com.fulcrologic.guardrails-analyzer.analysis2.purity-spec
  "Behavioral tests for the analysis2 purity namespace. Targets `pure-and-runnable?`
   (the gate used by path-based partitioning) along with its supporting helpers."
  (:require
   [com.fulcrologic.guardrails-analyzer.analysis2.purity :as sut]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn- base-env
  "A real env produced by build-env with the default test fdef registries loaded."
  []
  (cp.art/build-env {:NS "test.ns" :file "test.clj"}))

(defn- with-fn
  "Add a function-detail under the env's current ns at `fn-name` with the given `arities`."
  [env fn-name arities]
  (assoc-in env [::cp.art/function-registry "test.ns" fn-name]
            {::cp.art/fn-name fn-name
             ::cp.art/fn-ref  (constantly nil)
             ::cp.art/arities arities}))

(defn- arity
  "Build an arity-detail with the given arglist and gspec metadata map."
  [arglist metadata]
  {::cp.art/arglist arglist
   ::cp.art/gspec   {::cp.art/return-spec int?
                     ::cp.art/return-type :int
                     ::cp.art/metadata    metadata}})

;; ============================================================================
;; pure? multimethod
;; ============================================================================

(specification "pure? multimethod"
               (let [env (base-env)]
                 (assertions
                  "treats numbers as pure (literal)"
                  (sut/pure? env 42) => true
                  "treats strings as pure (literal)"
                  (sut/pure? env "hello") => true
                  "treats nil as pure (literal)"
                  (sut/pure? env nil) => true
                  "treats keywords as pure (ifn/literal dispatch)"
                  (sut/pure? env :foo) => true
                  "treats boolean true as pure (literal/boolean dispatch)"
                  (sut/pure? env true) => true
                  "treats boolean false as pure (literal/boolean dispatch)"
                  (sut/pure? env false) => true
                  "treats locally-resolvable symbols as pure"
                  (sut/pure? env 'x) => true
                  "treats vector literals as pure"
                  (sut/pure? env [1 2 3]) => true
                  "treats set literals as pure"
                  (sut/pure? env #{1 2 3}) => true
                  "treats map literals as pure"
                  (sut/pure? env {:a 1 :b 2}) => true
                  "treats meta-wrapped literal forms as pure"
                  (sut/pure? env {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                                  :value                                            42
                                  :kind                                             :number})
                  => true
                  "treats `if` special form as not pure (cannot directly partition)"
                  (sut/pure? env '(if x y z)) => false
                  "default dispatch (unrecognized list expression) is not pure"
                  (sut/pure? env '(some-unregistered-fn x)) => false)))

;; ============================================================================
;; pure-function?
;; ============================================================================

(specification "pure-function?"
               (let [env (base-env)]
                 (component "via known-pure-core-functions list"
                            (assertions
                             "returns true for a known-pure arithmetic function"
                             (sut/pure-function? env '+) => true
                             "returns true for a known-pure predicate"
                             (sut/pure-function? env 'even?) => true
                             "returns true for a known-pure collection accessor"
                             (sut/pure-function? env 'get) => true
                             "returns true for a fully-qualified known-pure function"
                             (sut/pure-function? env 'clojure.core/inc) => true))

                 (component "via :pure metadata in fdefs"
                            (assertions
                             "returns true for clojure.core/apply (registered with ^:pure)"
                             (sut/pure-function? env 'apply) => true
                             "returns true for clojure.core/constantly (registered with ^:pure)"
                             (sut/pure-function? env 'constantly) => true))

                 (component "for impure registered functions"
                            (assertions
                             "returns false for clojure.core/println (no purity metadata)"
                             (sut/pure-function? env 'println) => false
                             "returns false for clojure.core/swap! (no purity metadata)"
                             (sut/pure-function? env 'swap!) => false))

                 (component "for user-defined >defns with :pure? metadata"
                            (let [env+ (with-fn env 'my-pure-fn
                                         {1 (arity '[x] {:pure? true})})]
                              (assertions
                               "returns true when any arity carries :pure? metadata"
                               (sut/pure-function? env+ 'my-pure-fn) => true)))

                 (component "for user-defined >defns with :pure (shorthand) metadata"
                            (let [env+ (with-fn env 'shorthand-pure
                                         {1 (arity '[x] {:pure true})})]
                              (assertions
                               "returns true when shorthand :pure metadata is present"
                               (sut/pure-function? env+ 'shorthand-pure) => true)))

                 (component "for user-defined >defns without purity metadata"
                            (let [env+ (with-fn env 'plain-fn
                                         {1 (arity '[x] {})})]
                              (assertions
                               "returns false when no purity metadata is present"
                               (sut/pure-function? env+ 'plain-fn) => false)))))

;; ============================================================================
;; has-pure-mock?
;; ============================================================================

(specification "has-pure-mock?"
               (let [env (base-env)]
                 (component "with :pure-mock metadata"
                            (let [env+ (with-fn env 'mocked-fn
                                         {1 (arity '[x] {:pure-mock (fn [_] 42)})})]
                              (assertions
                               "returns true when metadata contains :pure-mock"
                               (sut/has-pure-mock? env+ 'mocked-fn) => true)))

                 (component "without :pure-mock metadata"
                            (let [env+ (with-fn env 'plain-fn
                                         {1 (arity '[x] {:pure? true})})]
                              (assertions
                               "returns false when no arity declares :pure-mock"
                               (sut/has-pure-mock? env+ 'plain-fn) => false)))

                 (component "arity-specific :pure-mock"
                            (let [env+ (with-fn env 'multi-arity-fn
                                         {1 (arity '[x] {})
                                          2 (arity '[x y] {:pure-mock (fn [_ _] 99)})})]
                              (assertions
                               "returns true when ANY arity declares :pure-mock"
                               (sut/has-pure-mock? env+ 'multi-arity-fn) => true)))))

;; ============================================================================
;; get-pure-mock
;; ============================================================================

(specification "get-pure-mock"
               (let [env  (base-env)
                     mock (fn [x] (* x 2))]
                 (component "returns the pure-mock function when present"
                            (let [env+ (with-fn env 'mocked-fn
                                         {1 (arity '[x] {:pure-mock mock})})]
                              (assertions
                               "returns the function attached to :pure-mock metadata"
                               (sut/get-pure-mock env+ 'mocked-fn) => mock
                               "the returned function is callable and returns the expected value"
                               ((sut/get-pure-mock env+ 'mocked-fn) 21) => 42)))

                 (component "returns nil when no :pure-mock metadata is present"
                            (let [env+ (with-fn env 'plain-fn
                                         {1 (arity '[x] {:pure? true})})]
                              (assertions
                               "returns nil when no arity declares :pure-mock"
                               (sut/get-pure-mock env+ 'plain-fn) => nil)))

                 (component "arity-specific mock is reachable"
                            (let [arity-2-mock (fn [_ _] :two-arg-result)
                                  env+         (with-fn env 'multi-arity-fn
                                                 {1 (arity '[x] {})
                                                  2 (arity '[x y] {:pure-mock arity-2-mock})})]
                              (assertions
                               "returns the mock from the arity that declared it"
                               (sut/get-pure-mock env+ 'multi-arity-fn) => arity-2-mock)))))

;; ============================================================================
;; expr-is-pure?
;; ============================================================================

(specification "expr-is-pure?"
               (let [env (base-env)]
                 (assertions
                  "literal numbers are pure"
                  (sut/expr-is-pure? env 42) => true
                  "literal strings are pure"
                  (sut/expr-is-pure? env "hi") => true
                  "symbols (locals) are pure"
                  (sut/expr-is-pure? env 'x) => true
                  "calls to impure functions are NOT pure"
                  (sut/expr-is-pure? env '(println x)) => false
                  "calls with impure subexpressions are NOT pure"
                  (sut/expr-is-pure? env '(+ 1 (println 2))) => false
                  "if expressions are NOT pure (special-form dispatch)"
                  (sut/expr-is-pure? env '(if x 1 2)) => false)))

;; ============================================================================
;; pure-and-runnable?  (the gate for path partitioning)
;; ============================================================================

(specification "pure-and-runnable?"
               (let [env (base-env)]
                 (component "literals and locals"
                            (assertions
                             "a literal number is runnable"
                             (sut/pure-and-runnable? env 42) => true
                             "a literal string is runnable"
                             (sut/pure-and-runnable? env "x") => true
                             "a literal boolean is runnable"
                             (sut/pure-and-runnable? env true) => true
                             "a symbol (treated as local lookup) is runnable"
                             (sut/pure-and-runnable? env 'x) => true))

                 (component "calls to a pure core function"
                            (assertions
                             "(even? x) is runnable (pure core predicate over a local)"
                             (sut/pure-and-runnable? env '(even? x)) => true
                             "(< x 10) is runnable (pure core comparison)"
                             (sut/pure-and-runnable? env '(< x 10)) => true
                             "(+ 1 2) is runnable (pure core arithmetic with literals)"
                             (sut/pure-and-runnable? env '(+ 1 2)) => true))

                 (component "calls to an impure function"
                            (assertions
                             "(println x) is NOT runnable"
                             (sut/pure-and-runnable? env '(println x)) => false
                             "(swap! a inc) is NOT runnable"
                             (sut/pure-and-runnable? env '(swap! a inc)) => false))

                 (component "calls to an unresolved function symbol"
                            (assertions
                             "(unknown-fn x) is NOT runnable (no fn-ref and no pure-mock)"
                             (boolean (sut/pure-and-runnable? env '(unknown-fn x))) => false))

                 (component "calls to a function with :pure-mock metadata"
                            (let [env+ (with-fn env 'mocked-fn
                                         {1 (arity '[x] {:pure-mock (fn [v] (* 2 v))})})]
                              (assertions
                               "becomes runnable because of the pure-mock"
                               (sut/pure-and-runnable? env+ '(mocked-fn x)) => true)))

                 (component "arity-specific :pure-mock"
                            (let [env+ (with-fn env 'multi
                                         {1 (arity '[x] {})
                                          2 (arity '[x y] {:pure-mock (fn [_ _] :ok)})})]
                              (assertions
                               "presence of pure-mock on ANY arity makes the call runnable"
                               (sut/pure-and-runnable? env+ '(multi x)) => true
                               (sut/pure-and-runnable? env+ '(multi x y)) => true)))

                 (component "argument purity is required transitively"
                            (assertions
                             "pure head with impure argument is NOT runnable"
                             (sut/pure-and-runnable? env '(even? (println x))) => false
                             "pure head with all-pure nested args IS runnable"
                             (sut/pure-and-runnable? env '(even? (+ x 1))) => true))

                 (component "non-symbol heads"
                            (assertions
                             "calls whose head is not a symbol are NOT runnable"
                             (sut/pure-and-runnable? env '((:foo bar) 1)) => false))))
