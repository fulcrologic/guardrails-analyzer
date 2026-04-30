(ns com.fulcrologic.guardrails-analyzer.path-explosion-spec
  "Regression test that path-explosion limits are enforced during analysis.

   `apply-path-limits` is wired through `analyze-if-determined` /
   `analyze-if-undetermined` (see `analyzer/macros.cljc`) and runs
   `deduplicate-paths` + per-path sample truncation + path-count cap.
   Dynamic vars `cp.art/*max-paths*` (500) and
   `cp.art/*max-samples-per-path*` (20) are defined in artifacts.cljc.

   Each pure predicate below (`even?`, `odd?`, `pos?`, `neg?`, `zero?`)
   is recognised as pure-and-runnable, so each layer of `if`/`cond`/`and`/
   `or` is analysed via `analyze-if-determined` and produces a fresh
   then/else split. The 12-condition ladder does NOT multiply out to
   2^12 = 4096 paths in practice because:

   - `cond`/`and`/`or` are short-circuit, so each leaf only carries the
     conditions on its own branch path, not all 12.
   - Many leaves return the same constant (`:ka`, `:kb`, `:end`, `nil`,
     `true`/`false` from `and`/`or`), and `deduplicate-paths` merges
     paths whose `::samples` are identical.

   After deduplication the analyzer settles on ~5–10 paths for this
   form. The `result` leaf is bound to 50 distinct samples and gets
   truncated to `*max-samples-per-path*` = 20, demonstrating that the
   per-path sample cap is active even though `*max-paths*` is never
   reached for this input."
  (:require
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer :as cp.ana]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [=> assertions specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(def ^:private mixed-int-samples
  "Sample set that spans negatives, zero, and positives so that EVERY predicate
   in the ladder below (`even?`, `odd?`, `pos?`, `neg?`, `zero?`) actually
   splits — i.e. partitions samples into both true and false sides. The
   original `(range 1 11)` was all positive, which made `pos?` always-true,
   `neg?` always-false, `zero?` always-false, etc. Half the conditions then
   degenerated to a single-branch path and the cartesian-product never
   materialized."
  (set (range -5 6)))

(defn explosion-env
  "Build a test env with bound symbols `a` .. `l` (mixed-sign int samples)
   and `result` (50 distinct samples). Each predicate condition below
   tests one of these symbols so partitioning produces determined
   then/else paths on both sides."
  []
  (-> (cp.art/build-env {:NS "test.ns" :file "test.clj"})
      (cp.art/remember-local 'a {::cp.art/samples mixed-int-samples})
      (cp.art/remember-local 'b {::cp.art/samples mixed-int-samples})
      (cp.art/remember-local 'c {::cp.art/samples mixed-int-samples})
      (cp.art/remember-local 'd {::cp.art/samples mixed-int-samples})
      (cp.art/remember-local 'e {::cp.art/samples mixed-int-samples})
      (cp.art/remember-local 'f {::cp.art/samples mixed-int-samples})
      (cp.art/remember-local 'g {::cp.art/samples mixed-int-samples})
      (cp.art/remember-local 'h {::cp.art/samples mixed-int-samples})
      (cp.art/remember-local 'i {::cp.art/samples mixed-int-samples})
      (cp.art/remember-local 'j {::cp.art/samples mixed-int-samples})
      (cp.art/remember-local 'k {::cp.art/samples mixed-int-samples})
      (cp.art/remember-local 'l {::cp.art/samples mixed-int-samples})
      (cp.art/remember-local 'result {::cp.art/samples (set (range 0 50))})))

(def deeply-nested-form
  "12-condition ladder mixing if / cond / and / or / when / when-not.

   Condition tally (each becomes an `if` after macro rewriting):
     1.  (even? a)        — outer if
     2.  (pos? b)         — nested if
     3.  (odd? c)         — cond clause
     4.  (zero? d)        — cond clause
     5.  (neg? e)         — cond clause
     6.  (pos? f)         — and arg #1
     7.  (even? g)        — and arg #2
     8.  (odd? h)         — or arg #1
     9.  (zero? i)        — or arg #2
     10. (neg? j)         — nested if
     11. (even? k)        — when
     12. (pos? l)         — when-not"
  '(if (even? a)
     (if (pos? b)
       (cond
         (odd? c)  result
         (zero? d) :ka
         (neg? e)  :kb
         :else     (and (pos? f) (even? g)))
       (or (odd? h) (zero? i)))
     (if (neg? j)
       (when (even? k)
         (when-not (pos? l) :end))
       :other)))

(specification "path-explosion limits are enforced"
               (let [env   (explosion-env)
                     td    (cp.ana/analyze! env deeply-nested-form)
                     paths (::cp.art/execution-paths td)]
                 (assertions
                  "Analyzer produces at least one execution path (not flat samples)"
                  (pos? (count paths))
                  => true

                  "Analyzer produces multiple paths from the 12-condition ladder
                   (witness lower bound — proves the if/cond/and/or analyzers
                   actually fork rather than collapsing to a single path)"
                  (>= (count paths) 5)
                  => true

                  "Total execution-paths count is bounded by `*max-paths*`"
                  (<= (count paths) cp.art/*max-paths*)
                  => true

                  "Per-path sample count is bounded by `*max-samples-per-path*`
                   (the `result` leaf carries 50 samples pre-limit and is
                   truncated to 20 by `apply-path-limits`)"
                  (every? #(<= (count (::cp.art/samples %)) cp.art/*max-samples-per-path*)
                          paths)
                  => true

                  "At least one path actually got its samples truncated to the
                   cap (proves the per-path sample limit is exercised, not
                   vacuously satisfied)"
                  (boolean
                   (some #(= cp.art/*max-samples-per-path*
                             (count (::cp.art/samples %)))
                         paths))
                  => true)))
