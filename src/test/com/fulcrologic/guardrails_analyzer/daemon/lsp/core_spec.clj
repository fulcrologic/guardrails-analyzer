(ns com.fulcrologic.guardrails-analyzer.daemon.lsp.core-spec
  "Tests for the LSP `lsp-server` mount state.

   `daemon/lsp/core.clj` is a thin wiring namespace: it defines a single
   `defstate` that delegates lifecycle to `lsp.server/start-lsp` and
   `lsp.server/stop-lsp`. The behaviors we verify are:

     * the state is registered with mount under the fully-qualified name
     * `:start` delegates to `lsp.server/start-lsp`
     * the `lsp-server` var is bound to whatever `start-lsp` returned
     * `:stop` delegates to `lsp.server/stop-lsp`
     * `:stop` passes the started value (NOT a fresh call to `start-lsp`)
       through to `stop-lsp` as its single argument

   We mock `lsp.server/start-lsp` and `lsp.server/stop-lsp` with `with-redefs`
   so the test never opens a real ServerSocket, never writes to the user's
   `~/.guardrails/lsp-server.port` file, and never spawns an `async/go-loop`.
   Each test takes care to leave the mount state stopped on the way in AND
   on the way out so test ordering does not matter."
  (:require
   [com.fulcrologic.guardrails-analyzer.daemon.lsp.core :as sut]
   [com.fulcrologic.guardrails-analyzer.daemon.lsp.server :as lsp.server]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions specification]]
   [mount.core :as mount]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(def ^:private lsp-server-state-name
  "The fully-qualified state name mount uses to key the `lsp-server` defstate
   in `mount.core/meta-state`. Mirrors the macro's `with-ns` formatting:
   `\"#'<ns>/<name>\"`."
  "#'com.fulcrologic.guardrails-analyzer.daemon.lsp.core/lsp-server")

(specification "lsp-server defstate registration"
               (assertions
                "the lsp-server var is interned in the daemon.lsp.core namespace"
                (some? (resolve 'com.fulcrologic.guardrails-analyzer.daemon.lsp.core/lsp-server))
                => true

                "lsp-server is registered with mount as a managed state"
                (contains? (set (mount/find-all-states)) lsp-server-state-name)
                => true))

(defn- run-lifecycle!
  "Drives the `lsp-server` mount state through one `start` / `stop` cycle
   with `start-lsp` and `stop-lsp` stubbed.

   The stubs record everything the lifecycle does in the returned map:

     * `:start-calls`    - number of times `start-lsp` was invoked
     * `:stop-args`      - vector of argument-vectors passed to `stop-lsp`
                           (one entry per call)
     * `:after-start`    - the value of the `lsp-server` var observed BETWEEN
                           start and stop (this is what mount alter-var-root'd
                           the var to after `start-lsp` returned)

   We always call `mount/stop` in a `finally` so a test failure cannot leave
   the daemon-level state machine half-started."
  [fake-state]
  (let [start-calls (atom 0)
        stop-args   (atom [])
        after-start (atom ::not-captured)]
    ;; Defensive: in case a previous test left this state running, force-stop
    ;; before redef'ing so the in-flight `:stop` body doesn't see the stub.
    (mount/stop #'sut/lsp-server)
    (with-redefs [lsp.server/start-lsp (fn []
                                         (swap! start-calls inc)
                                         fake-state)
                  lsp.server/stop-lsp  (fn [& args]
                                         (swap! stop-args conj (vec args))
                                         nil)]
      (try
        (mount/start #'sut/lsp-server)
        (reset! after-start (var-get #'sut/lsp-server))
        (finally
          (mount/stop #'sut/lsp-server))))
    {:start-calls @start-calls
     :stop-args   @stop-args
     :after-start @after-start}))

(specification "lsp-server :start delegates to lsp.server/start-lsp"
               (let [fake-state {::lsp.server/port-file :fake-port-file
                                 ::lsp.server/stop-chan :fake-stop-chan}
                     {:keys [start-calls after-start]} (run-lifecycle! fake-state)]
                 (assertions
                  "calls lsp.server/start-lsp exactly once during mount/start"
                  start-calls => 1

                  "binds the lsp-server var to the value returned by start-lsp"
                  after-start => fake-state)))

(specification "lsp-server :stop delegates to lsp.server/stop-lsp"
               (let [fake-state {::lsp.server/port-file :fake-port-file
                                 ::lsp.server/stop-chan :fake-stop-chan}
                     {:keys [stop-args]} (run-lifecycle! fake-state)]
                 (assertions
                  "calls lsp.server/stop-lsp exactly once during mount/stop"
                  (count stop-args) => 1

                  "passes a single argument to stop-lsp (its arity-1 contract)"
                  (count (first stop-args)) => 1

                  "passes the value start-lsp returned (not a fresh start-lsp call)"
                  (ffirst stop-args) => fake-state)))

(specification "lsp-server lifecycle is independent across cycles"
               (let [first-state  {::lsp.server/port-file :first-port
                                   ::lsp.server/stop-chan :first-chan}
                     second-state {::lsp.server/port-file :second-port
                                   ::lsp.server/stop-chan :second-chan}
                     first-cycle  (run-lifecycle! first-state)
                     second-cycle (run-lifecycle! second-state)]
                 (assertions
                  "second start invocation calls start-lsp again (state restarts cleanly)"
                  (:start-calls second-cycle) => 1

                  "second start binds the var to the second start-lsp return value"
                  (:after-start second-cycle) => second-state

                  "second stop receives the second cycle's started value (no leakage from first cycle)"
                  (ffirst (:stop-args second-cycle)) => second-state

                  "first cycle's stop received its own started value (sanity for the contract)"
                  (ffirst (:stop-args first-cycle)) => first-state)))
