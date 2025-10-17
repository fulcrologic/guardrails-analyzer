(ns com.fulcrologic.guardrails-analyzer.analysis.purity-data
  "Pure function data that can be shared without circular dependencies.

  This namespace contains data about which functions are known to be pure,
  allowing both artifacts.cljc and purity.cljc to access this information
  without creating circular dependencies.")

;; ============================================================================
;; Known Pure Functions
;; ============================================================================

(def known-pure-core-functions
  "Set of clojure.core functions that are known to be pure (no side effects, deterministic)"
  #{;; Numeric predicates
    'even? 'odd? 'pos? 'neg? 'zero?
    'pos-int? 'neg-int? 'nat-int?

    ;; Type predicates
    'nil? 'some? 'true? 'false? 'boolean?
    'number? 'integer? 'int? 'float? 'double?
    'string? 'keyword? 'symbol? 'map? 'vector? 'set? 'list? 'seq? 'seqable?
    'coll? 'sequential? 'associative? 'sorted?
    'fn? 'ifn?

    ;; Comparison
    '= '== 'not= '< '<= '> '>= 'compare
    'identical? 'distinct?

    ;; Logic
    'not

    ;; Arithmetic (pure but may throw on division by zero)
    '+ '- '* '/ 'quot 'rem 'mod
    'inc 'dec 'max 'min 'abs

    ;; String operations
    'str 'subs

    ;; Collection operations (pure, return new collections)
    'get 'get-in 'contains? 'find
    'first 'second 'rest 'next 'last 'butlast
    'take 'drop 'take-while 'drop-while
    'conj 'assoc 'dissoc 'update 'update-in
    'keys 'vals 'select-keys
    'concat 'into 'merge
    'count 'empty? 'not-empty})

(defn known-pure-function?
  "Check if a function symbol is in the known-pure-core-functions list.
  Handles both qualified and unqualified symbols."
  [fn-sym]
  (or
    ;; Check unqualified symbol
    (contains? known-pure-core-functions fn-sym)
    ;; Check qualified symbol by name
    (when (qualified-symbol? fn-sym)
      (contains? known-pure-core-functions (symbol (name fn-sym))))))
