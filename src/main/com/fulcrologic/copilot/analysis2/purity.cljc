(ns com.fulcrologic.copilot.analysis2.purity
  (:require
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as d]
    [com.fulcrologic.copilot.analysis.purity-data :as purity-data]
    [com.fulcrologic.copilot.analysis.sampler :as cp.sampler]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.guardrails.core :refer [=> >defn ?]]))

(defmulti pure?
  "A multimethod that can determine if an expression is pure,
   and therefore directly runnable during checking. Returns true if the expression
   is known to be pure, false otherwise."
  (fn [env expression] (d/analyze-dispatch env expression)))

(defmethod pure? :default [_env _expression] false)

;; Literal values are pure
(defmethod pure? :unknown [_env expression]
  ;; Check if it's a literal value (number, string, boolean, keyword, nil)
  (or (number? expression)
    (string? expression)
    (boolean? expression)
    (keyword? expression)
    (nil? expression)))
(defmethod pure? :symbol.local/lookup [_env _expression] true)
(defmethod pure? :literal/wrapped [_env _expression] true)
(defmethod pure? :collection/vector [_env _expression] true)
(defmethod pure? :collection/set [_env _expression] true)
(defmethod pure? :collection/map [_env _expression] true)

(defmethod pure? :ifn/literal [_env _expression] true)

(defmethod pure? :literal/boolean [_env _expression] true)

;; TASK: IF is pure if its three parts are all pure
(defmethod pure? 'if [env expression] false)
(defmethod pure? 'clojure.core/if [env expression] false)
(defmethod pure? 'cljs.core/if [env expression] false)

;; Use shared known-pure-core-functions from purity-data namespace
(def known-pure-core-functions
  "Set of clojure.core functions that are known to be pure (no side effects, deterministic).
  Delegates to purity-data/known-pure-core-functions to avoid circular dependencies."
  purity-data/known-pure-core-functions)

;; ============================================================================
;; Helper Functions for Purity Checking
;; ============================================================================

(>defn pure-function?
  "Check if a function symbol has pure metadata in its gspec,
or is in the known-pure-core-functions list.
Returns true if the function is marked as pure via :pure? or :pure metadata,
or is a known-pure clojure.core function."
  [env fn-sym]
  [::cp.art/env symbol? => boolean?]
  (or
    ;; Check known-pure list (handles both qualified and unqualified symbols)
    (contains? known-pure-core-functions fn-sym)
    (when (qualified-symbol? fn-sym)
      (contains? known-pure-core-functions (symbol (name fn-sym))))
    ;; Check metadata in function registry
    (when-let [fn-detail (or (cp.art/function-detail env fn-sym)
                           (cp.art/external-function-detail env fn-sym))]
      (let [arities (::cp.art/arities fn-detail)]
        ;; Check if any arity has pure metadata
        (boolean
          (some (fn [[_arity arity-detail]]
                  (when-let [gspec (::cp.art/gspec arity-detail)]
                    (let [metadata (cp.sampler/convert-shorthand-metadata
                                     (::cp.art/metadata gspec))]
                      (or (get metadata ::cp.sampler/pure)
                        (get metadata :pure?)))))
            arities))))))

(>defn has-pure-mock?
  "Check if a function has a :pure-mock available for evaluation.
Pure mocks allow us to evaluate functions during analysis without side effects."
  [env fn-sym]
  [::cp.art/env symbol? => boolean?]
  (when-let [fn-detail (or (cp.art/function-detail env fn-sym)
                         (cp.art/external-function-detail env fn-sym))]
    (let [arities (::cp.art/arities fn-detail)]
      (boolean
        (some (fn [[_arity arity-detail]]
                (when-let [gspec (::cp.art/gspec arity-detail)]
                  (let [metadata (cp.sampler/convert-shorthand-metadata
                                   (::cp.art/metadata gspec))]
                    (contains? metadata :pure-mock))))
          arities)))))

(>defn get-pure-mock
  "Get the pure-mock function for a given function symbol.
Returns the mock function or nil if not available."
  [env fn-sym]
  [::cp.art/env symbol? => (? fn?)]
  (when-let [fn-detail (or (cp.art/function-detail env fn-sym)
                         (cp.art/external-function-detail env fn-sym))]
    (let [arities (::cp.art/arities fn-detail)]
      (some (fn [[_arity arity-detail]]
              (when-let [gspec (::cp.art/gspec arity-detail)]
                (let [metadata (cp.sampler/convert-shorthand-metadata
                                 (::cp.art/metadata gspec))]
                  (:pure-mock metadata))))
        arities))))

(declare expr-is-pure?)

(>defn expr-is-pure?
  "Recursively check if an expression is pure.
An expression is pure if:
- It's a literal, local binding, or pure collection
- It's a function call where the function is pure and all arguments are pure"
  [env expr]
  [::cp.art/env any? => boolean?]
  (cond
    ;; Use the multimethod for basic dispatch
    (not (seq? expr))
    (pure? env expr)

    ;; For function calls, check if function and all args are pure
    (seq? expr)
    (let [[head & args] expr
          dispatch-key (d/analyze-dispatch env expr)]
      (cond
        ;; Special forms and macros - check via multimethod
        (keyword? dispatch-key)
        (pure? env expr)

        ;; Function calls - check function purity and args
        (symbol? head)
        (and (pure-function? env head)
          (every? #(expr-is-pure? env %) args))

        :else false))

    :else false))

(>defn pure-and-runnable?
  "Check if an expression is pure and can be safely evaluated during analysis.
This is stricter than just checking purity - it also verifies we have
the necessary functions available (either real implementations or pure-mocks)."
  [env expr]
  [::cp.art/env any? => boolean?]
  (cond
    ;; Literals and locals are always runnable
    (not (seq? expr))
    (pure? env expr)

    ;; For function calls, verify we can run them
    (seq? expr)
    (let [[head & args] expr
          dispatch-key (d/analyze-dispatch env expr)]
      (cond
        ;; Function calls (both internal and external)
        ;; Check if it's a function call by looking at the head symbol
        (symbol? head)
        (and (or (pure-function? env head)
               (has-pure-mock? env head))
          (every? #(pure-and-runnable? env %) args))

        ;; Special forms and macros - only if explicitly marked as pure
        (keyword? dispatch-key)
        (and (pure? env expr)
          (every? #(pure-and-runnable? env %) args))

        :else false))

    :else false))
