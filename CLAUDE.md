# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Copilot is a static analysis tool for Clojure/ClojureScript that performs type checking and validation based on Guardrails specs (a concise inline spec syntax for clojure.spec and Malli). It analyzes code at compile-time or development-time to catch type errors and spec violations without runtime overhead.

This project has a close relationship with the **Guardrails** library (located at `../guardrails`), which provides the `>defn` macro and inline gspec syntax that Copilot analyzes.

See the ai directory for architecture and testing documentation.

## Key Architecture

### Core Components

1. **Analyzer System** (`src/main/com/fulcrologic/copilot/analysis/`)
   - `analyzer.cljc` - Main entry point for analysis via `analyze!` function
   - `analyzer/dispatch.cljc` - Multi-method dispatch system for different expression types
   - `analyzer/functions.cljc` - Analyzes function definitions and calls
   - `analyzer/macros.cljc` - Macro expansion and analysis
   - `analyzer/literals.cljc` - Literal value analysis
   - `analyzer/hofs.cljc` - Higher-order function analysis
   - `analyzer/ifn.cljc` - IFn interface analysis
   - `function_type.cljc` - Type checking and validation for function arguments/returns
   - `sampler.cljc` - Generates sample data using spec generators for validation
   - `spec.cljc` - Spec system integration and caching
   - `destructuring.cljc` - Handles destructuring forms

2. **Artifacts System** (`artifacts.cljc`)
   - Central data structure definitions using clojure.spec
   - Manages the environment (`::env`) that tracks bindings, problems, and analysis state
   - Functions for recording errors, warnings, and type information
   - Key specs: `::type-description`, `::problem-type`, `::gspec`, `::arity-detail`

3. **Checker** (`checker.cljc`)
   - Main entry point: `check!` function processes forms from editor
   - `gather-analysis!` collects problems and bindings after analysis
   - Formats output for IDE/LSP consumption

4. **Daemon System** (`src/daemon/`)
   - `lsp/` - Language Server Protocol implementation
   - `server/` - HTTP server and WebSocket communication
   - Provides real-time feedback to editors/IDEs

5. **UI Formatters** (`src/main/com/fulcrologic/copilot/ui/`)
   - `problem_formatter.cljc` - Formats type errors for display
   - `binding_formatter.cljc` - Formats binding information for display

### Analysis Flow

1. Editor sends code forms to Copilot daemon
2. `checker/check!` sets up environment and clears previous state
3. Each form is analyzed via `analyzer/analyze!`
4. Analysis dispatches based on expression type (function call, literal, macro, etc.)
5. Type checking validates arguments against gspecs using generated samples
6. Problems are recorded in the artifacts system
7. Results are formatted and sent back to editor

### Key Concepts

- **gspec**: Guardrails' inline spec syntax `[arg-specs* => ret-spec]`
- **Type Description**: Data structure representing the inferred/declared type of an expression
- **Environment (`::env`)**: Analysis context containing:
  - Local bindings and their types
  - Function definitions and their gspecs
  - External function references
  - Current file/namespace context
- **Sampler**: Uses spec generators to create test data for validation
- **fdefs**: Function definitions for core Clojure functions (`analysis/fdefs/`)

### Path-Based Analysis (NEW - 2025-10-15)

Copilot now features **path-based analysis** that tracks execution paths through conditional branches for precise error reporting.

#### Core Concepts

- **Execution Path**: A sequence of conditional branches with associated samples and bindings
- **Path-Based Type Description**: Type description with `::execution-paths` instead of flat `::samples`
- **Determined Condition**: Pure predicate where samples can be partitioned (e.g., `(even? x)`)
- **Undetermined Condition**: Non-pure condition using superposition (both branches see all samples)
- **Sample Partitioning**: Splitting samples based on condition evaluation for each path

#### How It Works

1. **Conditional Analysis**: When analyzing `if`/`when`/`cond`, the analyzer:
   - Checks if condition is pure and runnable (`pure-and-runnable?`)
   - **If pure**: Partitions samples by evaluating condition on each sample
   - **If not pure**: Uses superposition (both branches see all samples)

2. **Path Tracking**: Each execution path tracks:
   - `::conditions` - Chain of conditions leading to this path
   - `::samples` - Sample values for this specific path
   - `::path-bindings` - Variable bindings filtered for this path

3. **Error Reporting**: Errors include `::failing-paths` showing exactly which execution paths violate specs

#### Key Files

**Core Implementation:**
- `artifacts.cljc` - Path specs (lines 138-173), helpers (308-414), partitioning (416-534), limits (536-588)
- `analyzer/macros.cljc` - `analyze-if-determined` (70-113), `analyze-if-undetermined` (115-138), updated `if` analyzer (140-174)
- `analysis2/purity.cljc` - Purity checking for predicates
- `function_type.cljc` - Path-aware `check-return-type!` validation
- `ui/problem_formatter.cljc` - Path formatting helpers and updated error formatters

**Key Functions:**
```clojure
;; Path management
(cp.art/path-based? type-description)
(cp.art/ensure-path-based type-description)
(cp.art/extract-all-samples type-description)
(cp.art/create-single-path samples bindings)

;; Condition tracking
(cp.art/add-determined-condition path condition-id expr location condition-value branch)
(cp.art/add-undetermined-condition path condition-id expr location branch)

;; Sample partitioning
(cp.art/partition-samples-by-condition env condition sym samples)
(cp.art/update-binding-with-samples env sym samples)

;; Path limits (prevent explosion)
(cp.art/deduplicate-paths paths)
(cp.art/apply-path-limits paths)
```

#### Control Flow Support

These constructs work with path-based analysis via **manual rewriting** to `if`:

- **Direct**: `if`, `when`, `when-not`, `if-not`, `if-let`, `when-let`
- **Nested**: `cond`, `and`, `or` 
- **Threading**: `cond->`, `cond->>`, `some->`, `some->>`

The analyzer manually rewrites these forms to `if`-based equivalents, then analyzes them:

```clojure
;; Example: cond analyzer manually constructs nested if
(defmethod analyze-mm 'clojure.core/cond [env [_ & clauses]]
  (letfn [(COND [clauses]
            (when-let [[tst expr & rst] (seq clauses)]
              `(if ~tst ~expr ~(COND rst))))]
    (analyze! env (COND clauses))))
```

#### Documentation

See comprehensive documentation in the repository root:

- **`PATH_BASED_ANALYSIS_COMPLETE.md`** - Complete implementation summary (all 6 phases)
- **`IMPLEMENTATION_STATUS.md`** - Detailed status tracking with examples
- **`PLAN.md`** - Original implementation plan and success criteria
- **`PHASE4_COMPLETE.md`** - Spec validation implementation details
- **`PHASE5_COMPLETE.md`** - Error formatting implementation details
- **`PHASE6_ANALYSIS.md`** - Control flow constructs analysis

#### Testing

- **Unit tests**: `artifacts_spec.clj` - Comprehensive path spec tests
- **REPL tests**: `src/dev/path_analysis_repl_tests.clj` - Manual verification suite
- **Control flow tests**: `src/dev/control_flow_tests.clj` - Control flow verification
- **All tests pass**: 40 tests, 157 assertions, 0 failures, zero regressions

#### Error Message Examples

Path-based errors show exactly which execution paths fail:

```
Single path:
"The Return spec is boolean?, but it is possible to return a value like 42 when (< 8 n 11) → else"

Multiple paths:
"The Return spec is number?, but it is possible to return a value like "bad", 42 on 2 paths:
  • (pos? x) → then
  • (pos? x) → else"

Nested conditions:
"The Return spec is number?, but it is possible to return a value like :bad when (pos? x) → then AND (even? x) → else"
```

#### Important Notes

1. **Manual Rewriting**: The analyzer manually constructs `if`-based forms; it does NOT call Clojure's `macroexpand`
2. **Backward Compatible**: Non-path-based type descriptions still work
3. **Path Explosion**: Mitigated via deduplication, sample limits (20/path), and path limits (500 total)
4. **Current Limitations**: 
   - Simple conditions only (single-symbol predicates like `(even? x)`)
   - Complex conditions fall back to superposition
   - Arithmetic operations don't yet propagate filtered samples

## Configuration Files

- `deps.edn` - Main dependency and alias configuration
- `shadow-cljs.edn` - ClojureScript build configuration
- `tests.edn` - Kaocha test runner configuration
- `guardrails.edn` - Guardrails configuration (throw behavior, expound settings)
- `guardrails-test.edn` - Guardrails config for tests

## Source Directory Structure

- `src/main/` - Core analysis and checking logic (cross-platform .cljc files)
- `src/daemon/` - LSP server and HTTP/WebSocket daemon
- `src/daemon_main/` - Main entry point for daemon
- `src/test/` - Test specs using fulcro-spec
- `src/test_cases/` - Real-world test cases for analysis validation
- `src/system_test/` - System-level integration tests
- `src/dev/` - Development utilities
- `src/timesheets/` - Timesheet tracking utility

## Important Implementation Details

### Adding New Analysis Support

When adding support for a new form type:

1. Add a dispatch method in `analyzer/dispatch.cljc`
2. Implement analysis logic in appropriate analyzer namespace
3. Return a `::type-description` from the analyzer
4. Add test cases in `src/test_cases/` to validate behavior
5. Add specs if introducing new artifact types in `artifacts.cljc`

### Working with Specs

- Copilot uses clojure.spec extensively for internal data validation
- The `artifacts` namespace defines all core specs
- Use `>defn` from Guardrails for function definitions (practice what we preach!)
- Spec cache is managed in `analysis/spec.cljc` with `with-empty-cache`

### Error Recording

Use functions from `artifacts.cljc`:
- `record-error!` - Type errors and validation failures
- `record-warning!` - Advisory issues (unable to check, etc.)
- `record-info!` - Informational messages

### Relationship with Guardrails

Copilot analyzes code that uses Guardrails' `>defn` macro. When working on Copilot:
- Changes may require corresponding updates in Guardrails repository
- Test with latest Guardrails from `../guardrails` during development
- Guardrails provides the gspec syntax; Copilot performs the static analysis
- `fdefs` in `analysis/fdefs/` define gspecs for Clojure core functions

## Testing Strategy

1. **Unit tests** (`-spec.clj` files) - Test individual analyzers and utilities
2. **Integration tests** - Test full analysis pipeline with metadata `:integration`
3. **Test cases** (`src/test_cases/`) - Real-world code patterns to validate
4. **System tests** - Test daemon, LSP, and full integration

When adding features, create test cases in `src/test_cases/` that demonstrate the expected behavior.
