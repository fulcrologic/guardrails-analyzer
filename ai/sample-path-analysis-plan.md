# Sample-Based Path-Sensitive Analysis Plan

## Core Understanding

Copilot performs **generative sample-based data flow analysis**:

1. Generate concrete sample values from specs (e.g., `int?` → `#{-1 0 1 42 ...}`)
2. Propagate **sets of samples** through code to track possible values
3. Validate samples against specs at usage points

**Key insight:** This is NOT type checking. We're tracking concrete example values to detect data flow problems.

## The Path Problem

### Current Issue (line 91 in `analyzer/macros.cljc`)

```clojure
{::cp.art/samples (cp.sampler/random-samples-from env T E)}
```

This randomly mixes samples from then/else branches, losing information about which execution path produced each sample.

### What We Need

**Each path must track:**
1. **Multiple samples** (not just one) - a set of possible values for that path
2. **Path conditions** - how we got to this path
3. **Determinacy** - do we know the sample partition, or just possibilities?

## Core Concepts

### 1. Execution Path with Sample Set

```clojure
{:path-id 0
 :conditions [{:condition-id 0
               :expression '(even? a)
               :location {:line 3 :column 5}
               :determined? true          ; We could evaluate the condition
               :branch :then}]
 :samples #{4 8}                         ; Multiple samples for this path
 :bindings {'a #{4 8}}}                  ; Bound variables with their samples
```

**Key:** Each path carries multiple samples AND the binding environment for that path.

### 2. Pure vs Non-Pure Conditionals

#### Pure and Runnable - Sample Partitioning

```clojure
(let [a #{1 4 5 8 9}]  ; Input samples
  (if (even? a)         ; Pure predicate we can run
    ...))
```

We can **partition** samples by evaluating `even?`:
- Path 1 (then): `a ∈ #{4 8}` (filtered: samples that satisfy even?)
- Path 2 (else): `a ∈ #{1 5 9}` (filtered: samples that fail even?)

**This is DETERMINED** - we know exactly which samples go to which path.

#### Non-Pure or Unknown - Superposition

```clojure
(let [a #{1 4 5 8 9}]
  (if (external-api-call? a)  ; Can't run this!
    "hello"
    {:x 1}))
```

We **cannot partition** `a` samples, so we create **superposition**:
- Path 1: `b = #{"hello"}`, `a = #{1 4 5 8 9}` (all original samples)
- Path 2: `b = #{#:x 1}`, `a = #{1 4 5 8 9}` (all original samples)

**This is UNDETERMINED** - we lost correlation between `a` and `b`.

### 3. Determinacy and Error Reporting

**Determined paths:**
- We can report errors on specific paths
- Example: "When `a` is even (samples: #{4 8}), operation fails"

**Undetermined paths (superposition):**
- Only report error if **ALL paths fail**
- Can't attribute error to specific input samples
- Example: "Operation fails on some code paths"

## Data Structure Design

### Path Representation

```clojure
(s/def ::path-id int?)

(s/def ::condition-id int?)
(s/def ::condition-expression any?)
(s/def ::condition-location (s/keys :req-un [::line ::column]))
(s/def ::determined? boolean?)  ; Can we partition samples?
(s/def ::branch keyword?)  ; :then, :else, :branch-0, etc.

(s/def ::path-condition
  (s/keys :req [::condition-id
                ::condition-expression
                ::condition-location
                ::determined?
                ::branch]))

(s/def ::conditions (s/coll-of ::path-condition))

;; A path is a set of samples with conditions and bindings
(s/def ::execution-path
  (s/keys :req [::path-id
                ::conditions
                ::samples        ; Set of samples for this path
                ::bindings]))    ; Map of var → samples for this path

(s/def ::execution-paths (s/coll-of ::execution-path :min-count 1))
```

### Type Description with Paths

```clojure
;; Replace ::samples with ::execution-paths
(s/def ::type-description
  (s/or
    :unknown (s/keys :req [::unknown-expression])
    :function ::lambda
    :path-based (s/keys :req [::execution-paths])  ; NEW
    :value (s/keys :opt [::spec ::type ::samples ...])))
```

**Example:**
```clojure
;; After (if (even? a) (+ a 10) (str a))
;; where a ∈ #{1 4 5 8 9}

{::execution-paths
 [{::path-id 0
   ::conditions [{::condition-id 0
                  ::condition-expression '(even? a)
                  ::determined? true
                  ::branch :then}]
   ::samples #{14 18}              ; (+ a 10) where a ∈ #{4 8}
   ::bindings {'a #{4 8}}}         ; Original samples filtered

  {::path-id 1
   ::conditions [{::condition-id 0
                  ::condition-expression '(even? a)
                  ::determined? true
                  ::branch :else}]
   ::samples #{"1" "5" "9"}        ; (str a) where a ∈ #{1 5 9}
   ::bindings {'a #{1 5 9}}}]}     ; Original samples filtered
```

## Implementation Strategy

### Phase 1: Core Infrastructure

#### 1.1 Path Specs (artifacts.cljc)

```clojure
;; Path condition
(s/def ::condition-id int?)
(s/def ::condition-expression any?)
(s/def ::condition-location (s/keys :req-un [::line ::column]))
(s/def ::determined? boolean?)
(s/def ::branch keyword?)

(s/def ::path-condition
  (s/keys :req [::condition-id
                ::condition-expression
                ::condition-location
                ::determined?
                ::branch]))

(s/def ::conditions (s/coll-of ::path-condition))

;; Execution path
(s/def ::path-id int?)
(s/def ::execution-path
  (s/keys :req [::path-id
                ::conditions
                ::samples      ; Set of samples
                ::bindings]))  ; Map of symbol → samples

(s/def ::execution-paths (s/coll-of ::execution-path :min-count 1))

;; Update type-description
(s/def ::type-description
  (s/or
    :unknown (s/keys :req [::unknown-expression])
    :function ::lambda
    :path-based (s/keys :req [::execution-paths])
    :value (s/keys :opt [::spec ::type ::samples ...])))
```

#### 1.2 Helper Functions

```clojure
(>defn path-based?
  "Check if type-description uses execution paths"
  [td]
  [::type-description => boolean?]
  (contains? td ::execution-paths))

(>defn ensure-path-based
  "Convert old-style samples to path-based form (single path, no conditions)"
  [td]
  [::type-description => ::type-description]
  (if (path-based? td)
    td
    (if-let [samples (seq (::samples td))]
      {::execution-paths
       [{::path-id 0
         ::conditions []
         ::samples (set samples)
         ::bindings {}}]}
      td)))

(>defn extract-all-samples
  "Get all samples across all paths (loses path info)"
  [td]
  [::type-description => ::samples]
  (if (path-based? td)
    (reduce set/union #{} (map ::samples (::execution-paths td)))
    (::samples td #{})))

(>defn create-single-path
  "Create a simple execution path with samples"
  [samples bindings]
  [::samples (s/map-of symbol? ::samples) => ::execution-path]
  {::path-id 0
   ::conditions []
   ::samples samples
   ::bindings bindings})
```

### Phase 2: Conditional Analysis - The Core Challenge

#### 2.1 Determine if Condition is Pure and Runnable

**Purity Metadata Design:**

Functions can declare purity via metadata:

```clojure
;; Simple purity declaration
(defn ^:pure? increment [x] (inc x))

;; Pure mock for sample propagation (implies :pure?)
(defn ^{:pure-mock (fn [x] (* x 2))} complex-calculation [x]
  ;; Complex impure logic here
  (* x 2))
```

- `:pure? true` - Declares function has no side effects, deterministic
- `:pure-mock fn` - Provides mock function for sample propagation (implies pure)
- Source extraction must capture this metadata into function registry

**Implementation:**

```clojure
(defn pure-and-runnable?
  "Check if we can evaluate condition on samples"
  [env condition-expr]
  ;; Check if condition references only:
  ;; 1. Pure functions (marked via :pure? or :pure-mock metadata)
  ;; 2. Literals
  ;; 3. Local bindings we have samples for
  (and (expr-is-pure? env condition-expr)
       (can-resolve-all-refs? env condition-expr)))

(defn expr-is-pure?
  "Check if expression uses only pure functions.
   Extends analysis2/purity.cljc multimethod approach."
  [env expr]
  (cond
    (not (seq? expr)) true  ; Literals, symbols
    :else
    (let [[f & args] expr]
      (and (pure-function? env f)
           (every? (partial expr-is-pure? env) args)))))

(defn pure-function?
  "Check if function is marked as pure via metadata.
   Returns true if function has :pure? or :pure-mock metadata."
  [env f-sym]
  (let [fn-meta (get-in env [::cp.art/external-function-registry f-sym
                             ::cp.art/metadata])]
    (or (:pure? fn-meta)
        (contains? fn-meta :pure-mock))))
```

#### 2.2 Partition Samples (Determined Case)

```clojure
(defn partition-samples-by-condition
  "Evaluate condition on each sample, partition into then/else sets"
  [env condition-expr sample-set]
  ;; Returns {:then #{...} :else #{...}}
  (try
    (let [then-samples (set (filter #(eval-condition env condition-expr %)
                              sample-set))
          else-samples (set/difference sample-set then-samples)]
      {:then then-samples
       :else else-samples
       :determined? true})
    (catch #?(:clj Exception :cljs :default) e
      (log/warn "Cannot evaluate condition on samples:" e)
      {:determined? false})))

(defn eval-condition
  "Evaluate condition-expr with sample value, return truthy result.
   Uses pure-mock when available, falls back to actual function."
  [env condition-expr sample]
  (cond
    ;; Handle function calls
    (and (seq? condition-expr)
         (symbol? (first condition-expr)))
    (let [[f & args] condition-expr
          f-impl (resolve-pure-function env f)
          evaluated-args (map #(eval-arg env % sample) args)]
      (apply f-impl evaluated-args))

    ;; Literals
    (not (seq? condition-expr))
    condition-expr

    ;; Conservative: can't evaluate
    :else
    (throw (ex-info "Cannot evaluate condition" {:expr condition-expr}))))

(defn resolve-pure-function
  "Get the implementation for a pure function.
   Prefers :pure-mock if available, otherwise returns actual function."
  [env f-sym]
  (let [fn-meta (get-in env [::cp.art/external-function-registry f-sym
                             ::cp.art/metadata])
        pure-mock (:pure-mock fn-meta)]
    (cond
      ;; Use mock if provided
      pure-mock
      pure-mock

      ;; For clojure.core functions, resolve directly
      (namespace f-sym)
      (resolve f-sym)

      ;; Try to resolve unqualified symbol
      :else
      (or (resolve f-sym)
          (throw (ex-info "Cannot resolve pure function" {:symbol f-sym}))))))
```

#### 2.3 Update If Analyzer - Determined Case

```clojure
(defmethod cp.ana.disp/analyze-mm 'clojure.core/if
  [env [_ condition then-expr else-expr :as if-expr]]
  (let [condition-id (::next-condition-id env 0)
        env (update env ::next-condition-id (fnil inc 0))
        location (cp.art/env-location env)

        ;; Analyze condition to get its samples
        condition-td (cp.ana.disp/-analyze! env condition)

        ;; Check if condition is pure and runnable
        runnable? (pure-and-runnable? env condition)

        result (if runnable?
                 ;; DETERMINED: Partition samples and analyze branches
                 (analyze-if-determined env condition condition-td then-expr else-expr
                                       condition-id location)

                 ;; UNDETERMINED: Superposition
                 (analyze-if-undetermined env condition then-expr else-expr
                                         condition-id location))]

    ;; Detect unreachable branches (when all samples lead to one branch)
    (detect-unreachable-branches! env condition condition-td result)

    result))
```

#### 2.4 Determined If Analysis

```clojure
(defn analyze-if-determined
  "Analyze if with sample partitioning (we can run the condition)"
  [env condition condition-td then-expr else-expr condition-id location]

  ;; Get input execution paths from condition
  (let [condition-paths (::execution-paths (ensure-path-based condition-td))

        ;; For each input path, partition its samples
        result-paths
        (mapcat
          (fn [{:keys [::path-id ::conditions ::samples ::bindings] :as input-path}]
            ;; Partition this path's samples
            (let [{:keys [then else determined?]}
                  (partition-samples-by-condition env condition samples)]

              ;; Create new paths for then/else branches
              [(when (seq then)
                 ;; Analyze then-expr with filtered samples
                 (let [env-then (update-env-with-samples env bindings then)
                       then-td (cp.ana.disp/-analyze! env-then then-expr)
                       then-paths (::execution-paths (ensure-path-based then-td))]
                   ;; Add condition to each resulting path
                   (map #(add-condition % condition-id condition location true :then)
                        then-paths)))

               (when (seq else)
                 ;; Analyze else-expr with filtered samples
                 (let [env-else (update-env-with-samples env bindings else)
                       else-td (if else-expr
                                (cp.ana.disp/-analyze! env-else else-expr)
                                {::execution-paths [(create-single-path #{nil} {})]})
                       else-paths (::execution-paths (ensure-path-based else-td))]
                   ;; Add condition to each resulting path
                   (map #(add-condition % condition-id condition location false :else)
                        else-paths)))]))

          condition-paths)]

    {::execution-paths (flatten (remove nil? result-paths))}))

(defn update-env-with-samples
  "Update environment bindings with filtered samples for this path"
  [env bindings filtered-samples]
  ;; Update each binding in env with its filtered samples
  (reduce-kv
    (fn [env bind-name original-samples]
      ;; Filter this binding's samples to only those in filtered-samples
      (assoc-in env [::local-bindings bind-name ::execution-paths]
        [{::path-id 0
          ::conditions []
          ::samples (set/intersection original-samples filtered-samples)
          ::bindings {}}]))
    env
    bindings))

(defn add-condition
  "Add a condition to an execution path"
  [path condition-id condition-expr location value branch]
  (update path ::conditions conj
    {::condition-id condition-id
     ::condition-expression condition-expr
     ::condition-location location
     ::determined? true
     ::condition-value value
     ::branch branch}))
```

#### 2.5 Undetermined If Analysis (Superposition)

```clojure
(defn analyze-if-undetermined
  "Analyze if when we cannot partition samples (superposition)"
  [env condition then-expr else-expr condition-id location]

  ;; We can't partition samples, so both branches see all samples
  ;; from the environment

  (let [;; Analyze both branches with full environment
        then-td (cp.ana.disp/-analyze! env then-expr)
        then-paths (::execution-paths (ensure-path-based then-td))

        else-td (if else-expr
                  (cp.ana.disp/-analyze! env else-expr)
                  {::execution-paths [(create-single-path #{nil} {})]})
        else-paths (::execution-paths (ensure-path-based else-td))

        ;; Mark conditions as undetermined
        then-paths-marked (map #(add-undetermined-condition % condition-id condition
                                                            location :then)
                            then-paths)
        else-paths-marked (map #(add-undetermined-condition % condition-id condition
                                                            location :else)
                            else-paths)]

    {::execution-paths (concat then-paths-marked else-paths-marked)}))

(defn add-undetermined-condition
  "Add an undetermined condition to path (superposition)"
  [path condition-id condition-expr location branch]
  (update path ::conditions conj
    {::condition-id condition-id
     ::condition-expression condition-expr
     ::condition-location location
     ::determined? false      ; Key difference: we don't know the partition
     ::branch branch}))
```

### Phase 3: Environment and Let Bindings

#### 3.1 Update Environment Structure

```clojure
;; Environment now tracks execution paths for each binding
(s/def ::local-bindings
  (s/map-of symbol? ::type-description))  ; Each binding is a type-description with paths

;; Add path tracking
(s/def ::next-condition-id int?)
(s/def ::next-path-id int?)
```

#### 3.2 Let Bindings with Path Propagation

```clojure
(defn analyze-let-bindings! [env bindings]
  ;; Current implementation already works!
  ;; reduce-kv cp.art/remember-local stores type-descriptions
  ;; which now contain execution-paths
  (reduce (fn [env [bind-sexpr sexpr]]
            (reduce-kv cp.art/remember-local
              env (cp.destr/destructure! env bind-sexpr
                    (cp.ana.disp/-analyze! env sexpr))))
    env (partition 2 bindings)))
```

**Key insight:** Minimal changes needed! Type-descriptions already flow through let bindings, and our path information is part of the type-description structure.

### Phase 4: Spec Validation with Path Reporting

#### 4.1 Validate Against Spec

```clojure
(>defn check-return-type!
  [env {::cp.art/keys [return-spec]} td original-expression]
  (let [td (ensure-path-based td)
        paths (::execution-paths td)

        ;; Check each path independently
        path-results
        (map (fn [{:keys [::path-id ::conditions ::samples ::bindings]}]
               (let [failures (filter #(not (cp.spec/valid? env return-spec %))
                                samples)]
                 {:path-id path-id
                  :conditions conditions
                  :determined? (every? ::determined? conditions)
                  :failures failures
                  :total-samples (count samples)}))
          paths)

        ;; Determine if we should report errors
        determined-paths (filter :determined? path-results)
        undetermined-paths (remove :determined? path-results)]

    ;; Report errors based on determinacy
    (if (seq determined-paths)
      ;; We have determined paths: report each failing path
      (doseq [{:keys [path-id conditions failures]} determined-paths
              :when (seq failures)]
        (cp.art/record-error! env
          {::original-expression original-expression
           ::path-id path-id
           ::path-conditions conditions
           ::failing-samples (set failures)
           ::expected {::spec return-spec}
           ::problem-type :error/bad-return-value}))

      ;; Only undetermined paths: only report if ALL paths fail
      (when (every? (fn [{:keys [failures total-samples]}]
                      (= (count failures) total-samples))
                undetermined-paths)
        (cp.art/record-error! env
          {::original-expression original-expression
           ::undetermined-paths undetermined-paths
           ::failing-samples (set (mapcat :failures undetermined-paths))
           ::expected {::spec return-spec}
           ::problem-type :error/bad-return-value-all-paths})))))
```

#### 4.2 Argument Validation

```clojure
(>defn validate-argtypes!?
  [env {::cp.art/keys [arglist gspec]} argtypes]
  (let [;; Ensure all arguments are path-based
        argtypes (map ensure-path-based argtypes)

        ;; Generate combinations of paths across arguments
        path-combos (apply combo/cartesian-product
                      (map ::execution-paths argtypes))

        ;; Check each combination
        combo-results
        (map (fn [path-combo]
               (let [;; Combine samples from each arg's path
                     arg-samples (map ::samples path-combo)
                     arg-paths (map ::conditions path-combo)
                     determined? (every? (fn [conds] (every? ::determined? conds))
                                   arg-paths)

                     ;; Generate sample combinations and check
                     sample-combos (apply combo/cartesian-product arg-samples)
                     failures (filter (fn [args]
                                        (not (args-satisfy-gspec? env gspec args)))
                                sample-combos)]

                 {:determined? determined?
                  :paths arg-paths
                  :failures failures
                  :total-combos (count sample-combos)}))
          path-combos)]

    ;; Error reporting logic (similar to check-return-type!)
    (report-argument-errors! env combo-results)

    ;; Return true if no errors
    (not-any? :failures combo-results)))
```

### Phase 5: Cond and Multi-Branch Support

```clojure
(defmethod cp.ana.disp/analyze-mm 'clojure.core/cond
  [env [_ & clauses]]
  (if (empty? clauses)
    {::execution-paths [(create-single-path #{nil} {})]}

    (let [condition-id (::next-condition-id env 0)
          env (update env ::next-condition-id (fnil inc 0))
          location (cp.art/env-location env)]

      ;; Process each test/expr pair
      (loop [remaining-clauses (partition 2 clauses)
             branch-num 0
             accumulated-paths []]

        (if-not (seq remaining-clauses)
          {::execution-paths accumulated-paths}

          (let [[test-expr result-expr] (first remaining-clauses)
                runnable? (pure-and-runnable? env test-expr)

                branch-paths
                (if runnable?
                  (analyze-cond-branch-determined env test-expr result-expr
                                                  condition-id location branch-num)
                  (analyze-cond-branch-undetermined env result-expr
                                                    condition-id location branch-num))

                new-paths (concat accumulated-paths branch-paths)]

            (recur (rest remaining-clauses)
                   (inc branch-num)
                   new-paths)))))))
```

## Path Explosion Mitigation

### Strategy 1: Deduplication

Many paths may produce identical samples:

```clojure
(defn deduplicate-paths
  "Merge paths with identical samples"
  [paths]
  (->> paths
       (group-by ::samples)
       (vals)
       (map (fn [same-sample-paths]
              (if (= 1 (count same-sample-paths))
                (first same-sample-paths)
                ;; Merge: keep samples, mark multiple condition sets
                {::path-id (::path-id (first same-sample-paths))
                 ::samples (::samples (first same-sample-paths))
                 ::bindings (::bindings (first same-sample-paths))
                 ::condition-sets (map ::conditions same-sample-paths)
                 ::merged? true})))))
```

### Strategy 2: Path Limits

```clojure
(def ^:dynamic *max-execution-paths* 500)

(defn limit-paths
  "Limit execution paths, merge if exceeded"
  [paths]
  (if (<= (count paths) *max-execution-paths*)
    paths
    (do
      (log/warn "Path limit exceeded, merging" (count paths) "paths")
      ;; Merge into single undetermined path
      [{::path-id 0
        ::conditions [{::condition-expression ::too-many-paths
                       ::determined? false}]
        ::samples (reduce set/union #{} (map ::samples paths))
        ::bindings (merge-bindings (map ::bindings paths))
        ::merged? true}])))
```

### Strategy 3: Sample Limit Per Path

```clojure
(def ^:dynamic *max-samples-per-path* 20)

(defn limit-samples-per-path
  "Limit samples in each path to prevent explosion"
  [paths]
  (map (fn [path]
         (if (<= (count (::samples path)) *max-samples-per-path*)
           path
           (update path ::samples #(set (take *max-samples-per-path* %)))))
    paths))
```

## Error Message Formatting

### Determined Path Error

```
ERROR: Return value does not satisfy spec
Location: example.clj:15:5
Expression: (format-person person)

Expected: (s/keys :req [::name ::age ::address])
Failing samples: {::name "Bob" ::age 42}
Missing keys: [::address]

This error occurs when:
  ✓ (add-address? user) was falsy (line 8)
    → then branch taken
  ✓ (premium? user) was truthy (line 12)
    → then branch taken

In this execution path:
  - Variable 'user' had samples: #{<user1> <user2>}
  - Variable 'person' had samples: {::name "Bob" ::age 42}

Suggestion: Ensure ::address is added in all code paths.
```

### Undetermined Path Error (All Paths Fail)

```
ERROR: Return value does not satisfy spec in all code paths
Location: example.clj:15:5
Expression: (validate-data data)

Expected: (s/keys :req [::validated? ::timestamp])
Failing samples: {::validated? true}, {::timestamp 123456}, {}

Note: Analysis could not determine which conditions lead to which samples.
This error occurs in ALL possible execution paths:
  • Path 1: (external-check? data) → then branch
  • Path 2: (external-check? data) → else branch

Suggestion: Ensure all branches return valid data with required keys.
```

## Implementation Phases

### Phase 1: Core Infrastructure (2 weeks)

**Week 1:**
- Add execution-path specs to `artifacts.cljc`
- Implement helper functions (`ensure-path-based`, `extract-all-samples`, etc.)
- Add purity checking infrastructure (`pure-and-runnable?`)
- Unit tests for path manipulation

**Week 2:**
- Implement sample partitioning (`partition-samples-by-condition`)
- Implement mini-evaluator for pure predicates (`eval-condition`)
- Add path deduplication and limits
- Unit tests for partitioning

**Deliverable:** Path infrastructure ready, can partition samples

### Phase 2: Determined If (2 weeks)

**Week 3:**
- Update `if` analyzer for determined case
- Implement `analyze-if-determined`
- Update environment to track paths
- Basic integration test

**Week 4:**
- Test with multiple ifs, nested ifs
- Test sample filtering correctness
- Verify bindings update correctly
- Enable simple test cases from `test_cases/flow_control/if.clj`

**Deliverable:** Determined if analysis working with sample partitioning

### Phase 3: Undetermined If / Superposition (1 week)

**Week 5:**
- Implement `analyze-if-undetermined`
- Test superposition cases
- Verify error reporting only when all paths fail
- Integration tests for mixed determined/undetermined

**Deliverable:** Full if support (both determined and undetermined)

### Phase 4: Spec Validation (1 week)

**Week 6:**
- Update `check-return-type!` with path-aware validation
- Update `validate-argtypes!?` for path combinations
- Implement error reporting logic (determined vs undetermined)
- Integration tests for error detection

**Deliverable:** Spec validation working with paths

### Phase 5: Error Formatting (1 week)

**Week 7:**
- Update `problem_formatter.cljc` for path display
- Format conditions clearly
- Test error messages for clarity
- Manual review of error messages

**Deliverable:** Beautiful error messages with path information

### Phase 6: Cond and Refinement (2 weeks)

**Week 8:**
- Implement `cond` analyzer
- Update `or`, `and` analyzers
- Complex nesting tests

**Week 9:**
- Path explosion tuning
- Performance optimization
- Enable all `test_cases/flow_control/` tests
- Bug fixes

**Deliverable:** Production-ready implementation

**Total: 9 weeks**

## Success Criteria

1. ✅ Samples correctly partitioned by pure conditions
2. ✅ Superposition correctly handled (undetermined cases)
3. ✅ Error reporting: specific for determined paths, conservative for undetermined
4. ✅ All tests in `test_cases/flow_control/if.clj` pass
5. ✅ All tests in `test_cases/flow_control/cond.clj` pass
6. ✅ Path explosion limited (< 500 paths)
7. ✅ Performance < 2x slowdown
8. ✅ No regressions

## Testing Strategy

### Unit Tests

```clojure
(specification "Sample partitioning"
  (let [samples #{1 2 3 4 5 6}
        result (partition-samples-by-condition env '(even? x) samples)]
    (assertions
      "Partitions into even/odd"
      (:then result) => #{2 4 6}
      (:else result) => #{1 3 5}
      (:determined? result) => true)))

(specification "Undetermined condition"
  (let [result (partition-samples-by-condition env '(unknown? x) samples)]
    (assertions
      "Cannot partition"
      (:determined? result) => false)))
```

### Integration Tests

```clojure
(>defn test-determined-if [x]
  [int? => int?]
  (let [a (if (even? x) 10 20)]
    (+ a x)))

;; Should track:
;; Path 1: x ∈ even-samples, a=10
;; Path 2: x ∈ odd-samples, a=20

;; Test with pure-mock
(defn ^{:pure-mock (fn [x] (even? x))} business-rule? [x]
  ;; Complex business logic with DB calls
  (db/check-rule x))

(>defn test-pure-mock [x]
  [int? => int?]
  (let [a (if (business-rule? x) 10 20)]
    (+ a x)))

;; Should track (using mock):
;; Path 1: x ∈ even-samples (mocked), a=10
;; Path 2: x ∈ odd-samples (mocked), a=20

(>defn test-undetermined-if [x]
  [int? => int?]
  (let [a (if (external-call? x) 10 20)]
    (+ a x)))

;; Should track:
;; Path 1: x ∈ all-samples, a=10 (undetermined)
;; Path 2: x ∈ all-samples, a=20 (undetermined)
```

## Design Decisions

1. **✅ Purity marking (DECIDED):**
   - Use metadata: `^:pure?` for simple purity declarations
   - Use `^{:pure-mock fn}` to provide mock for sample propagation (implies pure)
   - Mock allows authors to improve analysis by showing "typical" data flow
   - Source extraction must capture metadata into function registry

## Open Questions for Discussion

1. **Evaluator scope:** How extensive should the mini-evaluator be initially? Start with simple predicates and expand?

2. **Performance limits:** What are acceptable limits for paths (500?) and samples per path (20?)?

3. **Error reporting:** For undetermined paths, should we show "possibly fails" warnings even when some paths succeed?

4. **Mixed determinacy:** When some paths are determined and some undetermined, how aggressive should error reporting be?

5. **Core function purity:** Should we pre-mark common clojure.core functions as pure (even?, odd?, etc.) or require explicit metadata?
