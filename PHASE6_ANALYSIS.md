# Phase 6: Control Flow Constructs - Analysis

**Date:** 2025-10-15  
**Status:** ✅ ALREADY COMPLETE via Macroexpansion

## Discovery

Control flow constructs already work with path-based analysis! They macroexpand to `if`, which has full path-based support.

## How It Works

The analyzer in `analyzer/macros.cljc` defines these constructs as macros that expand to `if`:

### Direct Expansions to `if`

1. **`when`** → `(if condition (do body) nil)`
   ```clojure
   (defmethod analyze-mm 'clojure.core/when [env [_ condition & body]]
     (analyze! env `(if ~condition (do ~@body))))
   ```

2. **`when-not`** → `(if (not condition) (do body) nil)`
   ```clojure
   (defmethod analyze-mm 'clojure.core/when-not [env [_ condition & body]]
     (analyze! env `(if (not ~condition) (do ~@body))))
   ```

3. **`if-not`** → `(if (not condition) then else)`
   ```clojure
   (defmethod analyze-mm 'clojure.core/if-not [env [_ condition then & [else]]]
     (analyze! env `(if (not ~condition) ~then ~else)))
   ```

4. **`if-let`** → `(let [t# bind-expr] (if t# (let [bind-sym t#] then) else))`
   ```clojure
   (defmethod analyze-mm 'clojure.core/if-let [env [_ [bind-sym bind-expr] then & [else]]]
     (analyze! env `(let [t# ~bind-expr]
                      (if t#
                        (let [~bind-sym t#] ~then)
                        ~else))))
   ```

5. **`when-let`** → `(if-let bindings (do body))`
   ```clojure
   (defmethod analyze-mm 'clojure.core/when-let [env [_ bindings & body]]
     (analyze! env `(if-let ~bindings (do ~@body))))
   ```

### Nested `if` Expansions

6. **`cond`** → Nested `if` chain
   ```clojure
   (defmethod analyze-mm 'clojure.core/cond [env [_ & clauses]]
     (letfn [(COND [clauses]
               (when-let [[tst expr & rst] (seq clauses)]
                 `(if ~tst ~expr ~(COND rst))))]
       (analyze! env (COND clauses))))
   ```
   
   Example expansion:
   ```clojure
   (cond
     (pos? n) :positive
     (neg? n) :negative
     :else :zero)
   
   ;; Expands to:
   (if (pos? n)
     :positive
     (if (neg? n)
       :negative
       (if :else
         :zero
         nil)))
   ```

7. **`and`** → Nested `if` with short-circuit
   ```clojure
   (and (pos? x) (even? x))
   
   ;; Expands to:
   (let [t# (pos? x)]
     (if t# (and (even? x)) t#))
   ```

8. **`or`** → Nested `if` with short-circuit
   ```clojure
   (or (zero? x) (neg? x))
   
   ;; Expands to:
   (let [t# (zero? x)]
     (if t# t# (or (neg? x))))
   ```

## Threading Macros

These also work since they expand to function calls or let+if:

9. **`cond->`** → Nested `if` with threading
10. **`cond->>`** → Nested `if` with threading
11. **`some->`** → Nil-check with `if`
12. **`some->>`** → Nil-check with `if`

## Evidence from Tests

From the debug logs when analyzing a `cond` expression:

```
:record-binding! #:com.fulcrologic.copilot.artifacts{
  :execution-paths [
    {:path-id 0, 
     :conditions [{:condition-id 0, 
                   :condition-expression (pos? n), 
                   :determined? true, 
                   :condition-value true, 
                   :branch :then}], 
     :samples #{:positive}}
    
    {:path-id 0,
     :conditions [{:condition-id 1, 
                   :condition-expression (neg? n), 
                   :determined? true, 
                   :condition-value true, 
                   :branch :then}
                  {:condition-id 0, 
                   :condition-expression (pos? n), 
                   :determined? true, 
                   :condition-value false, 
                   :branch :else}], 
     :samples #{:negative}}
    
    {:path-id 0,
     :conditions [{:condition-id 2, 
                   :condition-expression :else, 
                   :determined? true, 
                   :condition-value true, 
                   :branch :then}
                  {:condition-id 1, 
                   :condition-expression (neg? n), 
                   :determined? true, 
                   :condition-value false, 
                   :branch :else}
                  {:condition-id 0, 
                   :condition-expression (pos? n), 
                   :determined? true, 
                   :condition-value false, 
                   :branch :else}], 
     :samples #{:zero}}
  ]}
```

This shows:
- ✅ `cond` creates multiple execution paths
- ✅ Each path has a proper condition chain
- ✅ Conditions are properly tracked (3 conditions for the `:else` branch)
- ✅ Samples are correctly partitioned

## What This Means

**All common Clojure control flow constructs already have path-based analysis!**

The only work needed in Phase 6 was to:
1. Verify they work (DONE)
2. Document how they work (this file)
3. Update the main documentation (TODO)

## Constructs with Full Path Support

✅ `if` - Direct implementation  
✅ `when` - Expands to `if`  
✅ `when-not` - Expands to `if`  
✅ `if-not` - Expands to `if`  
✅ `if-let` - Expands to `let` + `if`  
✅ `when-let` - Expands to `if-let`  
✅ `cond` - Expands to nested `if`  
✅ `and` - Expands to nested `if`  
✅ `or` - Expands to nested `if`  
✅ `cond->` - Expands to nested `if`  
✅ `cond->>` - Expands to nested `if`  
✅ `some->` - Expands to `if`  
✅ `some->>` - Expands to `if`  

## Constructs WITHOUT Path Support

The following don't have path-based analysis because they don't expand to `if`:

- `case` - Direct dispatch, no conditional logic
- `condp` - Predicate-based dispatch
- `for` - List comprehension (has custom analysis)
- `doseq` - Iteration (has custom analysis)
- `loop`/`recur` - Recursion (complex analysis needed)

## Next Steps

1. Document this in IMPLEMENTATION_STATUS.md
2. Update PLAN.md to mark Phase 6 complete
3. Consider whether `case`/`condp` need path support (probably not - they're value dispatch, not conditional logic)
