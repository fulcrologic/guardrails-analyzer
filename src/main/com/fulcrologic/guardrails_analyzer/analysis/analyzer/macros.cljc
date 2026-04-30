;; Copyright (c) Fulcrologic, LLC. All rights reserved.
;;
;; Permission to use this software requires that you
;; agree to our End-user License Agreement, legally obtain a license,
;; and use this software within the constraints of the terms specified
;; by said license.
;;
;; You may NOT publish, redistribute, or reproduce this software or its source
;; code in any form (printed, electronic, or otherwise) except as explicitly
;; allowed by your license agreement..

(ns com.fulcrologic.guardrails-analyzer.analysis.analyzer.macros
  (:require
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer.dispatch :as cp.ana.disp]
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer.literals]
   [com.fulcrologic.guardrails-analyzer.analysis.destructuring :as cp.destr]
   [com.fulcrologic.guardrails-analyzer.analysis.function-type :as cp.fnt]
   [com.fulcrologic.guardrails-analyzer.analysis.spec :as cp.spec]
   [com.fulcrologic.guardrails-analyzer.analysis2.purity :as cp.purity]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails.core :as gr]
   [com.fulcrologic.guardrails-analyzer.log :as log]))

(declare analyze-if-undetermined)

(defmethod cp.ana.disp/analyze-mm 'comment [env sexpr] {})

;; Top-level malli registrations are not analyzable expressions but are not errors either.
(defmethod cp.ana.disp/analyze-mm 'com.fulcrologic.guardrails.malli.registry/register!
  [_env _sexpr]
  {})
(defmethod cp.ana.disp/analyze-mm 'malli.registry/register!
  [_env _sexpr]
  {})

(defn analyze-single-arity! [env defn-sym [arglist _ & body]]
  (let [fd          (cp.art/function-detail env defn-sym)
        spec-system (or (::cp.art/spec-system env) (cp.art/fn-spec-system fd))
        env         (cp.spec/with-spec-system env spec-system)
        gspec       (get-in fd [::cp.art/arities (count arglist) ::cp.art/gspec])
        env         (cp.fnt/bind-argument-types env arglist gspec)
        result      (cp.ana.disp/analyze-statements! env body)]
    (cp.fnt/check-return-type! env gspec result defn-sym)))

(defn analyze:>defn! [env [_ defn-sym & defn-forms :as sexpr]]
  (let [env     (assoc env ::cp.art/checking-sym defn-sym)
        arities (drop-while (some-fn string? map?) defn-forms)]
    (if (vector? (first arities))
      (analyze-single-arity! env defn-sym arities)
      (doseq [arity arities]
        (analyze-single-arity! env defn-sym arity))))
  {})

(defmethod cp.ana.disp/analyze-mm `gr/>defn [env sexpr] (analyze:>defn! env sexpr))
(defmethod cp.ana.disp/analyze-mm `gr/>defn- [env sexpr] (analyze:>defn! env sexpr))
(defmethod cp.ana.disp/analyze-mm 'com.fulcrologic.guardrails.malli.core/>defn [env sexpr]
  (analyze:>defn! (assoc env ::cp.art/spec-system :malli) sexpr))
(defmethod cp.ana.disp/analyze-mm 'com.fulcrologic.guardrails.malli.core/>defn- [env sexpr]
  (analyze:>defn! (assoc env ::cp.art/spec-system :malli) sexpr))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/do [env [_ & body]]
  (cp.ana.disp/analyze-statements! env body))

(defn analyze-let-bindings! [env bindings]
  (reduce (fn [env [bind-sexpr sexpr]]
            (let [;; Analyze the expression
                  td                (cp.ana.disp/-analyze! env sexpr)
                  ;; Destructure to get symbol->type-description map
                  destructured-syms (cp.destr/destructure! env bind-sexpr td)
                  ;; Remember the original expression for each bound symbol
                  env-with-exprs    (reduce (fn [env' sym]
                                              (cp.art/remember-binding-expression env' sym sexpr))
                                            env (keys destructured-syms))]
              ;; Remember the type descriptions for each symbol
              (reduce-kv cp.art/remember-local env-with-exprs destructured-syms)))
          env (partition 2 bindings)))

(defn analyze-let-like-form! [env [_ bindings & body]]
  (cp.ana.disp/analyze-statements!
   (analyze-let-bindings! env bindings)
   body))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/let [env sexpr]
  (analyze-let-like-form! env sexpr))

(defn analyze:>letfn [env [_ fns & body]]
  ;;TODO:
  ;; two pass analysis
  ;; 1. bind fn gspec's
  ;; 2. analyze fn bodies & letfn body
  )

;; ========== PATH-BASED IF ANALYSIS ==========

(defn analyze-if-determined
  "Analyze if with sample partitioning (we can run the condition to partition samples).

  For each execution path in the condition:
  1. Partition its samples by evaluating the condition
  2. Analyze then-branch with filtered samples (those that satisfy condition)
  3. Analyze else-branch with filtered samples (those that don't satisfy condition)
  4. Add condition tracking to resulting paths

  Currently handles simple conditions that test a single symbol (e.g., `(even? x)`).
  Also handles conditions that are bound symbols (e.g., `t#` from `(let [t# (even? x)] (if t# ...))`).
  TODO: Extend to handle complex conditions with multiple symbols."
  [env condition-expr condition-td then-expr else-expr condition-id location]
  (let [;; Helper to extract the symbol being tested in a simple condition.
        ;; Handles two shapes:
        ;;  - `(pred sym)` — partition `sym`'s samples by the predicate.
        ;;    E.g. `(even? x)` => `x`.
        ;;  - bare `sym`  — partition `sym`'s samples by truthiness. This is
        ;;    important for `or`'s rewrite `(let [t# s] (if t# t# default))`,
        ;;    where after binding-expression lookup we end up testing `s`
        ;;    directly. Without it, the falsey samples (e.g. `nil`) would
        ;;    leak into the truthy then-branch and produce spurious
        ;;    return-type errors.
        extract-tested-symbol (fn [expr]
                                (cond
                                  (symbol? expr) expr
                                  (and (seq? expr) (= 2 (count expr)))
                                  (let [[_pred sym] expr]
                                    (when (symbol? sym) sym))))

        ;; If condition is a simple symbol, look up its binding expression
        ;; E.g., (let [t# (map? v)] (if t# ...)) => use (map? v) as predicate-expr
        predicate-expr        (if (symbol? condition-expr)
                                (or (cp.art/lookup-binding-expression env condition-expr)
                                    condition-expr)
                                condition-expr)

        ;; Pick the symbol whose samples we will partition. If predicate-expr
        ;; can't be reduced to a symbol (e.g. it's a literal like `"default"`
        ;; from `or`'s rewrite of `(or s "default") => (let [t# "default"]
        ;; (if t# t# nil))`), fall back to the original `condition-expr`
        ;; symbol when it is a bound local. That way we still partition by
        ;; truthiness on the binding's actual samples instead of falling
        ;; through to the unfiltered Case-2 superposition.
        tested-sym            (or (extract-tested-symbol predicate-expr)
                                  (when (and (symbol? condition-expr)
                                             (contains? (::cp.art/local-symbols env)
                                                        condition-expr))
                                    condition-expr))
        ;; If we fell back, the predicate-expr fed to
        ;; `partition-samples-by-condition` must reference `tested-sym`
        ;; rather than the literal binding-expression.
        predicate-expr        (if (and tested-sym
                                       (= tested-sym condition-expr)
                                       (not= predicate-expr tested-sym))
                                tested-sym
                                predicate-expr)

        ;; Look up the symbol's samples from the environment (not from condition paths)
        sym-td                (when tested-sym (cp.art/symbol-detail env tested-sym))
        sym-samples           (when sym-td (cp.art/extract-all-samples sym-td))]

    (if (and tested-sym (seq sym-samples))
      ;; Case 1: Simple condition testing a single symbol with samples available
      (let [{:keys [true-samples false-samples determined?]}
            (cp.art/partition-samples-by-condition env predicate-expr tested-sym sym-samples)]

        (if-not determined?
          ;; Partitioning failed, fall back to undetermined
          (do
            (log/warn "Sample partitioning failed for condition, falling back to superposition")
            ;; Return undetermined paths
            (let [then-td    (cp.ana.disp/-analyze! env then-expr)
                  else-td    (if else-expr
                               (cp.ana.disp/-analyze! env else-expr)
                               {::cp.art/execution-paths
                                [(cp.art/create-single-path
                                  (set (cp.spec/sample env (cp.spec/generator env nil?)))
                                  {})]})
                  then-paths (::cp.art/execution-paths (cp.art/ensure-path-based then-td))
                  else-paths (::cp.art/execution-paths (cp.art/ensure-path-based else-td))]
              {::cp.art/execution-paths
               (cp.art/apply-path-limits
                (vec (concat
                      (map #(cp.art/add-undetermined-condition % condition-id condition-expr location :then)
                           then-paths)
                      (map #(cp.art/add-undetermined-condition % condition-id condition-expr location :else)
                           else-paths))))}))

          ;; Successfully partitioned - create filtered paths.
          ;; When the original `condition-expr` is a bare symbol that aliases
          ;; the tested-sym (e.g. `(let [t# s] (if t# t# default))` ->
          ;; condition-expr=`t#`, tested-sym=`s`), we also need to narrow
          ;; `t#`'s samples in the branch env so that analyzing `t#` in
          ;; e.g. the then-branch returns only the truthy partition. Without
          ;; this the then-branch leaks falsey values back through the alias.
          (let [aliased-cond-sym (when (and (symbol? condition-expr)
                                            (not= condition-expr tested-sym)
                                            (contains? (::cp.art/local-symbols env)
                                                       condition-expr))
                                   condition-expr)
                update-branch-env
                (fn [base-env partition-samples]
                  (cond-> (cp.art/update-binding-with-samples
                           base-env tested-sym partition-samples)
                    aliased-cond-sym
                    (cp.art/update-binding-with-samples
                     aliased-cond-sym partition-samples)))

                then-results
                (when (seq true-samples)
                  (let [;; Update environment: replace symbol's samples with filtered ones
                        env-then   (update-branch-env env true-samples)
                        ;; Analyze then-expr
                        then-td    (cp.ana.disp/-analyze! env-then then-expr)
                        then-paths (::cp.art/execution-paths (cp.art/ensure-path-based then-td))]
                    ;; Add condition to each resulting path, including filtered bindings
                    (map (fn [path]
                           (-> path
                               (update ::cp.art/path-bindings assoc tested-sym true-samples)
                               (cp.art/add-determined-condition condition-id condition-expr location true :then)))
                         then-paths)))

                else-results
                (when (seq false-samples)
                  (let [;; Update environment: replace symbol's samples with filtered ones
                        env-else   (update-branch-env env false-samples)
                        ;; Analyze else-expr (or nil if no else clause)
                        else-td    (if else-expr
                                     (cp.ana.disp/-analyze! env-else else-expr)
                                     {::cp.art/execution-paths
                                      [(cp.art/create-single-path
                                        (set (cp.spec/sample env (cp.spec/generator env nil?)))
                                        {})]})
                        else-paths (::cp.art/execution-paths (cp.art/ensure-path-based else-td))]
                    ;; Add condition to each resulting path, including filtered bindings
                    (map (fn [path]
                           (-> path
                               (update ::cp.art/path-bindings assoc tested-sym false-samples)
                               (cp.art/add-determined-condition condition-id condition-expr location false :else)))
                         else-paths)))

                all-paths (vec (concat then-results else-results))]

            (if (seq all-paths)
              {::cp.art/execution-paths (cp.art/apply-path-limits all-paths)}
              ;; Both partitions were empty - fall back to undetermined
              (analyze-if-undetermined env then-expr else-expr condition-id condition-expr location)))))

      ;; Case 2: Complex condition or no samples available - fall back to simple analysis
      (do
        (log/debug "Cannot partition samples for complex condition, using simple path split")
        (let [then-td    (cp.ana.disp/-analyze! env then-expr)
              else-td    (if else-expr
                           (cp.ana.disp/-analyze! env else-expr)
                           {::cp.art/execution-paths
                            [(cp.art/create-single-path
                              (set (cp.spec/sample env (cp.spec/generator env nil?)))
                              {})]})
              then-paths (::cp.art/execution-paths (cp.art/ensure-path-based then-td))
              else-paths (::cp.art/execution-paths (cp.art/ensure-path-based else-td))]
          {::cp.art/execution-paths
           (cp.art/apply-path-limits
            (vec (concat
                  (map #(cp.art/add-determined-condition % condition-id condition-expr location true :then)
                       then-paths)
                  (map #(cp.art/add-determined-condition % condition-id condition-expr location false :else)
                       else-paths))))})))))

(defn analyze-if-undetermined
  "Analyze if when we cannot partition samples (superposition).

  Both branches see all samples from the environment.
  Conditions are marked as undetermined."
  [env then-expr else-expr condition-id condition-expr location]
  (let [;; Analyze both branches with full environment
        then-td           (cp.ana.disp/-analyze! env then-expr)
        then-paths        (::cp.art/execution-paths (cp.art/ensure-path-based then-td))

        else-td           (if else-expr
                            (cp.ana.disp/-analyze! env else-expr)
                            {::cp.art/execution-paths
                             [(cp.art/create-single-path
                               (set (cp.spec/sample env (cp.spec/generator env nil?)))
                               {})]})
        else-paths        (::cp.art/execution-paths (cp.art/ensure-path-based else-td))

        ;; Mark conditions as undetermined
        then-paths-marked (map #(cp.art/add-undetermined-condition % condition-id condition-expr
                                                                   location :then)
                               then-paths)
        else-paths-marked (map #(cp.art/add-undetermined-condition % condition-id condition-expr
                                                                   location :else)
                               else-paths)]

    {::cp.art/execution-paths
     (cp.art/apply-path-limits (vec (concat then-paths-marked else-paths-marked)))}))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/if [env [_ condition then & [else]]]
  ;; Path-based analysis: check if condition is pure and runnable
  ;; If yes: partition samples by condition (determined)
  ;; If no: use superposition (undetermined)
  (let [;; Assign unique condition ID for this if expression
        condition-id (or (::cp.art/next-condition-id env) 0)
        env          (assoc env ::cp.art/next-condition-id (inc condition-id))
        location     (cp.art/env-location env {::cp.art/original-expression condition})

        ;; Analyze condition to get its type-description
        condition-td (cp.ana.disp/-analyze! env condition)

        ;; Check if condition is pure and runnable
        runnable?    (cp.purity/pure-and-runnable? env condition)

        ;; Use appropriate analysis strategy
        result       (if runnable?
                       ;; DETERMINED: Can partition samples by running the condition
                       (analyze-if-determined env condition condition-td then else condition-id location)

                       ;; UNDETERMINED: Use superposition (both branches see all samples)
                       (analyze-if-undetermined env then else condition-id condition location))

        ;; Keep existing warnings for unreachable branches (using old samples for now)
        C            condition-td]
    (log/debug "IF:" C "runnable?" runnable?)
    (when (not (some identity (::cp.art/samples C)))
      (cp.art/record-warning! env condition
                              :warning/if-condition-never-reaches-then-branch))
    (when (and else (not (some not (::cp.art/samples C))))
      (cp.art/record-warning! env condition
                              :warning/if-condition-never-reaches-else-branch))
    result))

(defn analyze-if-let!
  "Analyzer for `(if-let [bind-sym bind-expr] then else)`.

   Partitions bind-expr's samples by truthiness:
     - then-branch: bind-sym bound to the truthy partition
     - else-branch: bind-sym bound to the falsy partition (typically #{nil})

   Records `bind-sym` as a regular binding (visible to UI), and emits
   path-based execution-paths annotated with a determined condition on
   bind-expr so error messages can attribute failing values to the
   then/else branch."
  [env [bind-sym bind-expr] then else]
  (let [condition-id (or (::cp.art/next-condition-id env) 0)
        env          (assoc env ::cp.art/next-condition-id (inc condition-id))
        location     (cp.art/env-location env {::cp.art/original-expression bind-expr})
        bind-td      (cp.ana.disp/-analyze! env bind-expr)
        all-samples  (cp.art/extract-all-samples bind-td)
        truthy       (set (filter identity all-samples))
        falsy        (set (remove identity all-samples))
        td-type      (::cp.art/type bind-td)
        env          (cp.art/remember-binding-expression env bind-sym bind-expr)
        analyze-branch
        (fn [branch-samples branch-expr branch-kw cond-value]
          (when (seq branch-samples)
            (let [branch-td (cond-> {::cp.art/samples branch-samples}
                              td-type (assoc ::cp.art/type td-type))
                  env'      (cp.art/remember-local env bind-sym branch-td)]
              (cp.art/record-binding! env' bind-sym branch-td)
              (let [result-td  (if branch-expr
                                 (cp.ana.disp/-analyze! env' branch-expr)
                                 {::cp.art/execution-paths
                                  [(cp.art/create-single-path #{nil} {})]})
                    res-paths  (::cp.art/execution-paths
                                (cp.art/ensure-path-based result-td))]
                (map (fn [path]
                       (-> path
                           (update ::cp.art/path-bindings assoc bind-sym branch-samples)
                           (cp.art/add-determined-condition
                            condition-id bind-expr location cond-value branch-kw)))
                     res-paths)))))]
    {::cp.art/execution-paths
     (vec (concat (analyze-branch truthy then :then true)
                  (analyze-branch falsy  else :else false)))}))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/if-let
  [env [_ bindings then & [else]]]
  (analyze-if-let! env bindings then else))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/if-not [env [_ condition then & [else]]]
  (cp.ana.disp/-analyze! env
                         `(if (not ~condition) ~then ~else)))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/when [env [_ condition & body]]
  (cp.ana.disp/-analyze! env
                         `(if ~condition (do ~@body))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/when-let [env [_ bindings & body]]
  (cp.ana.disp/-analyze! env
                         `(if-let ~bindings (do ~@body))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/when-not [env [_ condition & body]]
  (cp.ana.disp/-analyze! env
                         `(if (not ~condition) (do ~@body))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/and [env [_ & exprs]]
  (if (empty? exprs)
    {::cp.art/samples #{true}}
    (letfn [(AND [exprs]
              (when-let [[expr & rst] (seq exprs)]
                `(let [t# ~expr]
                   (if t# ~(AND rst) t#))))]
      (cp.ana.disp/-analyze! env (AND exprs)))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/or [env [_ & exprs]]
  (if (empty? exprs)
    {::cp.art/samples #{nil}}
    (letfn [(OR [exprs]
              (when-let [[expr & rst] (seq exprs)]
                `(let [t# ~expr]
                   (if t# t# ~(OR rst)))))]
      (cp.ana.disp/-analyze! env (OR exprs)))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/cond [env [_ & clauses]]
  (if (empty? clauses)
    {::cp.art/samples #{nil}}
    (letfn [(COND [clauses]
              (when-let [[tst expr & rst] (seq clauses)]
                ;; Special-case the conventional `:else` final clause: it is
                ;; always truthy and always taken if reached, so emit `expr`
                ;; directly instead of `(if :else expr nil)`. Without this,
                ;; the rewrite plants an unreachable implicit-nil else path
                ;; that — once `check-return-type!` correctly reports falsey
                ;; failing samples — is reported as a spurious return-type
                ;; violation (e.g. `nil` not satisfying `string?`).
                ;; The reader wraps top-level forms in meta-wrappers, so the
                ;; raw `tst` may be `{::cp.art/meta-wrapper? true :value :else}`
                ;; rather than the bare keyword `:else`.
                (if (and (= :else (cp.art/unwrap-meta tst)) (nil? (seq rst)))
                  expr
                  `(if ~tst ~expr ~(COND rst)))))]
      (cp.ana.disp/-analyze! env (COND clauses)))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/-> [env [_ subject & args]]
  (cp.ana.disp/-analyze! env
                         (reduce (fn [subject step]
                                   (with-meta
                                     (if (seq? step)
                                       (apply list (first step) subject (rest step))
                                       (list step subject))
                                     (meta step)))
                                 subject args)))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/->> [env [_ subject & args]]
  (cp.ana.disp/-analyze! env
                         (reduce (fn [subject step]
                                   (with-meta
                                     (if (seq? step)
                                       (seq (conj (vec step) subject))
                                       (list step subject))
                                     (meta step)))
                                 subject args)))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/as-> [env [_ expr subject & args]]
  (analyze-let-like-form! env
                          ['_ (reduce (fn [bindings step]
                                        (conj bindings
                                              subject step))
                                      [subject expr] args)
                           subject]))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/some-> [env [_ subject & args]]
  (analyze-let-like-form! env
                          ['_ (reduce (fn [bindings step]
                                        (conj bindings
                                              subject `(if (nil? ~subject)
                                                         nil (~'-> ~subject ~step))))
                                      [] args)
                           subject]))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/some->> [env [_ subject & args]]
  (analyze-let-like-form! env
                          ['_ (reduce (fn [bindings step]
                                        (conj bindings
                                              subject `(if (nil? ~subject)
                                                         nil (~'->> ~subject ~step))))
                                      [] args)
                           subject]))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/cond-> [env [_ subject & args]]
  (analyze-let-like-form! env
                          ['_ (reduce (fn [bindings [tst step]]
                                        (conj bindings
                                              subject `(if ~tst
                                                         (~'-> ~subject ~step)
                                                         ~subject)))
                                      [] (partition 2 args))
                           subject]))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/cond->> [env [_ subject & args]]
  (analyze-let-like-form! env
                          ['_ (reduce (fn [bindings [tst step]]
                                        (conj bindings
                                              subject `(if ~tst
                                                         (~'->> ~subject ~step)
                                                         ~subject)))
                                      [] (partition 2 args))
                           subject]))

(defn analyze-for-bindings! [env bindings]
  (reduce (fn [env [bind-sexpr sexpr]]
            (case (cp.art/unwrap-meta bind-sexpr)
              :let (analyze-let-bindings! env sexpr)
              (:when :while) (do (cp.art/record-error! env bind-sexpr :warning/not-implemented) env)
              (reduce-kv cp.art/remember-local
                         env (cp.destr/destructure! env (cp.art/unwrap-meta bind-sexpr)
                                                    (let [td (cp.ana.disp/-analyze! env sexpr)]
                                                      (if-not (every? seqable? (::cp.art/samples td))
                                                        (do (cp.art/record-error! env sexpr
                                                                                  :error/expected-seqable-collection)
                                                            {})
                                                        (update td ::cp.art/samples
                                                                (comp set (partial mapcat identity)))))))))
          env (partition 2 bindings)))

(defn analyze-for-loop! [env bindings body]
  (-> env
      (analyze-for-bindings! bindings)
      (cp.ana.disp/analyze-statements! body)
      (update ::cp.art/samples (comp hash-set vec))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/for [env [_ bindings & body]]
  (analyze-for-loop! env bindings body))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/doseq [env [_ bindings & body]]
  (analyze-for-loop! env bindings body)
  {::cp.art/samples #{nil}})
