# Running the Guardrails Analyzer In-Process

## Current Architecture

```
Editor/IDE -> (websocket) -> Daemon <- (websocket) <- Checker (separate JVM)
                                                        |
                                                     analyzer.cljc (core engine)
```

The checker (`checkers/clojure.clj`) is a headless Fulcro app that:

1. Connects to the daemon via WebSocket (shaded Sente under `com.fulcrologicpro.*`)
2. Listens for push events (`:check!`, `:refresh-and-check!`)
3. Runs the analysis engine (`checker.cljc` -> `analyzer.cljc`)
4. Reports results back via Pathom mutations

## Good News: It's Already Close

The `start` function (`checkers/clojure.clj:78-121`) is already designed for non-blocking REPL use -- it returns control (unlike `start!` which blocks forever). The shaded `alternate-deps` jar relocates Fulcro, Sente, Timbre, etc. under `com.fulcrologicpro.*`, so there are no classpath conflicts with the user's own Fulcro/Timbre.

## Issues to Solve

### 1. tools.namespace/refresh Safety (Biggest Issue)

Currently only `prepared_check.cljc` has `^{:clojure.tools.namespace.repl/load false}`. When `refresh-and-check!` calls `(refresh :after ...)`, it reloads everything in `refresh-dirs`. Two problems:

- **If analyzer namespaces are in refresh-dirs**: They get reloaded, losing the WebSocket connection state in the `defonce APP` atom and potentially breaking mid-analysis.
- **If user calls their own `(refresh)`**: Same risk. The `defonce` on `APP` survives reload, but the websocket remote state, go-loops, and sente router do not.

**Fix**: Since the analyzer would be a JAR dependency (not source on disk), JARs aren't subject to `refresh`. This is naturally handled by distribution format.

### 2. User's Own Refresh Workflow Interaction

The checker's `refresh-and-check!` calls `clojure.tools.namespace.repl/refresh`, which is a global singleton -- there's only one set of refresh-dirs, one tracker. If the user has their own refresh setup (e.g., in their `dev/user.clj`), the analyzer's `set-refresh-dirs` call would clobber it.

**Fix**: Drop tools.namespace entirely. Use `io.github.tonsky/clj-reload` instead (same pattern as simplymeet). clj-reload supports `^:clj-reload/no-reload` metadata on namespaces to protect stateful namespaces from unload/load cycles (even `defonce` won't survive an explicit unload). Key namespaces to mark:
- `checkers/clojure.clj` (defonce APP)
- `artifacts.cljc` (defonce bindings, problems)
- `analytics.cljc` (defonce stats atoms)
- `analysis/spec.cljc` (defonce cache)
- `prepared_check.cljc` (defonce prepared-check)

### 3. :pro Mode Requirement

`-Dguardrails.mode=:pro` must be set at JVM startup. This changes how `>defn` macros expand -- they register specs for the analyzer to consume. This is already required regardless of where the checker runs, so it's not a new constraint, but it needs to be documented clearly for the in-process case.

### 4. Dependency Distribution / Classpath Conflicts

The user needs on their classpath:

- `guardrails-analyzer` (the analysis engine + checker)
- `alternate-deps` (shaded Fulcro/Sente/Timbre)
- `specter`, `core.async`, `test.check`, `tools.namespace`

Some of these (specter, core.async, test.check) could conflict with user versions. Currently handled by being in a separate VM.

**Decision**: Accept version alignment. Document minimum versions and let deps resolution handle it. Specter has already been removed as a dependency. core.async and test.check will NOT be vendorized.

### 5. Thread / Performance Concerns

Analysis runs synchronously on the websocket push-handler thread (a core.async go-loop). In the user's VM this means:

- Analysis CPU usage competes with the user's REPL work
- A long analysis won't block the REPL itself (different thread), but could slow things down
- Generator-based sampling (test.check) can be CPU-intensive

This is acceptable for dev workflow -- the analysis is on-demand (triggered by editor save).

## Recommended Design

```
User's REPL JVM
+-- User's project code (src/main, src/dev, etc.)
+-- guardrails (>defn macros, runtime checks)
+-- guardrails-analyzer (as :dev dependency, from JAR)
    +-- checker.cljc (analysis engine - CLJC, no state management concerns)
    +-- checkers/repl.clj (NEW - replaces checkers/clojure.clj for in-process use)
    +-- com.fulcrologicpro.* (shaded deps from alternate-deps JAR)
         <-> WebSocket to Daemon (separate process, unchanged)
```

### New checkers/repl.clj

A new entry point specifically for in-process use. It would:

1. **Use clj-reload**: Instead of tools.namespace, use `io.github.tonsky/clj-reload` for reloading. Configure via `reload/init` with appropriate dirs.

2. **Mark stateful namespaces**: Add `^:clj-reload/no-reload` metadata to namespaces with connection state, atoms, etc. that must survive reload cycles.

3. **Expose a simple API**:

```clojure
(require '[com.fulcrologic.guardrails-analyzer.checkers.repl :as ga])

;; Connect to daemon
(ga/start {:src-dirs ["src/main" "src/dev"]})

;; Manual check (optional - normally triggered by editor)
(ga/check-ns 'my.app.core)

;; Stop
(ga/stop)
```

4. **Lifecycle-safe**: Use `defonce` for all connection state. Tolerate the user's own refresh cycles without breaking.

## What Was Vendorized

Vendorized into source tree under `com.fulcrologicpro.*`:

- Fulcro, Sente, Timbre, Transit, EQL, cognitect libs

NOT vendorized (accept version alignment):

- `org.clojure/core.async`
- `org.clojure/test.check`

Removed entirely:

- `com.rpl/specter` -- replaced with direct Clojure code

## Feasibility Summary

| Aspect                       | Difficulty | Notes                                              |
|------------------------------|------------|----------------------------------------------------|
| WebSocket to daemon          | Easy       | Already works via shaded deps                      |
| Analysis engine              | Easy       | Pure CLJC, no VM coupling                          |
| Classpath conflicts          | Done       | Specter removed, core.async/test.check accepted     |
| Reload mechanism             | Medium     | Switch to clj-reload, mark no-reload namespaces     |
| User refresh safety          | Medium     | JAR deps naturally excluded from refresh            |
| Lifecycle / state management | Low        | defonce + careful shutdown                          |
| Performance                  | Low        | Acceptable for dev workflow                         |

## Progress

- [x] Vendorize alternate-deps into source tree (commit dcb16bc)
- [x] Remove specter dependency
- [ ] Switch from tools.namespace to clj-reload
- [ ] Add `^:clj-reload/no-reload` metadata to stateful namespaces
- [ ] Create `checkers/repl.clj` entry point for in-process use
- [ ] Document `:pro` mode requirement for in-process use
- [ ] Run tests to verify vendorized deps work end-to-end
