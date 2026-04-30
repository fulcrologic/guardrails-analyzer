(ns com.fulcrologic.guardrails-analyzer.daemon.lsp.commands-spec
  "Behavior coverage for the LSP-side command dispatch namespace.

  Targets src/daemon/.../daemon/lsp/commands.clj:
  - check-file!      - dispatches to daemon.check/check-file! when a checker
                       is registered for the path, otherwise reports
                       no-checker via lsp.diag/report-no-checker!.
  - check-root-form! - same dispatch logic with an additional line argument.
  - commands         - the whitelist map exposed to executeCommand.

  Includes a P2B path-traversal regression spec (un-skipped, expected to FAIL
  until P4) asserting that an editor-supplied path containing `..` or an
  absolute path outside the registered :project-dir is REJECTED before being
  forwarded to daemon.check/check-file!.

  P4 fix needed: canonicalize and assert under :project-dir in
  daemon/lsp/commands.clj."
  (:require
   [com.fulcrologic.guardrails-analyzer.daemon.lsp.commands :as sut]
   [com.fulcrologic.guardrails-analyzer.daemon.lsp.diagnostics :as lsp.diag]
   [com.fulcrologic.guardrails-analyzer.daemon.server.checkers :as daemon.check]
   [com.fulcrologic.guardrails-analyzer.daemon.server.connection-management :as cp.conn]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

;; ============================================================================
;; commands whitelist
;; ============================================================================

(specification "lsp.commands/commands whitelist"
               (assertions
                "Exposes exactly the two known command names"
                (set (keys sut/commands)) => #{"check-file!" "check-root-form!"}

                "'check-file!' resolves to lsp.commands/check-file!"
                (get sut/commands "check-file!") => sut/check-file!

                "'check-root-form!' resolves to lsp.commands/check-root-form!"
                (get sut/commands "check-root-form!") => sut/check-root-form!

                "Every entry is callable"
                (every? fn? (vals sut/commands)) => true))

;; ============================================================================
;; check-file! - dispatch to daemon.check/check-file!
;; ============================================================================

(specification "check-file! - dispatches when a checker exists for the path"
               (let [check-calls    (atom [])
                     no-checker     (atom [])
                     client-id      #uuid "00000000-0000-0000-0000-0000000000aa"
                     path           "/proj/src/foo.clj"
                     opts           {:refresh? false}]
                 (with-redefs [cp.conn/registered-checkers   (atom {:checker-1 {:project-dir  "/proj"
                                                                                :checker-type :clj}})
                               daemon.check/check-file!      (fn [ws checker-cid p o]
                                                               (swap! check-calls conj
                                                                      {:ws       ws
                                                                       :cid      checker-cid
                                                                       :path     p
                                                                       :opts     o}))
                               lsp.diag/report-no-checker!   (fn [cid p]
                                                               (swap! no-checker conj
                                                                      {:client-id cid :path p}))]
                   (sut/check-file! client-id path opts)
                   (assertions
                    "Invokes daemon.check/check-file! exactly once"
                    (count @check-calls) => 1

                    "Forwards the resolved checker-cid (looked up from registered-checkers)"
                    (:cid (first @check-calls)) => :checker-1

                    "Forwards the editor-supplied path unchanged"
                    (:path (first @check-calls)) => path

                    "Forwards the editor-supplied opts unchanged"
                    (:opts (first @check-calls)) => opts

                    "Does NOT report no-checker when a checker exists"
                    @no-checker => []))))

(specification "check-file! - reports no-checker when no checker exists for the path"
               (let [check-calls (atom [])
                     no-checker  (atom [])
                     client-id   #uuid "00000000-0000-0000-0000-0000000000bb"
                     path        "/some/other/file.clj"]
                 (with-redefs [cp.conn/registered-checkers (atom {})
                               daemon.check/check-file!    (fn [& args]
                                                             (swap! check-calls conj (vec args)))
                               lsp.diag/report-no-checker! (fn [cid p]
                                                             (swap! no-checker conj
                                                                    {:client-id cid :path p}))]
                   (sut/check-file! client-id path {})
                   (assertions
                    "Does NOT dispatch to daemon.check/check-file! when no checker matches"
                    @check-calls => []

                    "Reports no-checker exactly once"
                    (count @no-checker) => 1

                    "Forwards the LSP client-id to report-no-checker!"
                    (:client-id (first @no-checker)) => client-id

                    "Forwards the editor-supplied path to report-no-checker!"
                    (:path (first @no-checker)) => path))))

;; ============================================================================
;; check-root-form! - dispatch to daemon.check/check-root-form!
;; ============================================================================

(specification "check-root-form! - dispatches when a checker exists for the path"
               (let [check-calls (atom [])
                     no-checker  (atom [])
                     client-id   #uuid "00000000-0000-0000-0000-0000000000cc"
                     path        "/proj/src/bar.clj"
                     line        42
                     opts        {:refresh? true}]
                 (with-redefs [cp.conn/registered-checkers     (atom {:checker-1 {:project-dir  "/proj"
                                                                                  :checker-type :clj}})
                               daemon.check/check-root-form!   (fn [ws checker-cid p l o]
                                                                 (swap! check-calls conj
                                                                        {:ws   ws
                                                                         :cid  checker-cid
                                                                         :path p
                                                                         :line l
                                                                         :opts o}))
                               lsp.diag/report-no-checker!     (fn [cid p]
                                                                 (swap! no-checker conj
                                                                        {:client-id cid :path p}))]
                   (sut/check-root-form! client-id path line opts)
                   (assertions
                    "Invokes daemon.check/check-root-form! exactly once"
                    (count @check-calls) => 1

                    "Forwards the resolved checker-cid (looked up from registered-checkers)"
                    (:cid (first @check-calls)) => :checker-1

                    "Forwards the editor-supplied path unchanged"
                    (:path (first @check-calls)) => path

                    "Forwards the editor-supplied line argument unchanged"
                    (:line (first @check-calls)) => line

                    "Forwards the editor-supplied opts unchanged"
                    (:opts (first @check-calls)) => opts

                    "Does NOT report no-checker when a checker exists"
                    @no-checker => []))))

(specification "check-root-form! - reports no-checker when no checker exists for the path"
               (let [check-calls (atom [])
                     no-checker  (atom [])
                     client-id   #uuid "00000000-0000-0000-0000-0000000000dd"
                     path        "/no/match/here.clj"]
                 (with-redefs [cp.conn/registered-checkers   (atom {})
                               daemon.check/check-root-form! (fn [& args]
                                                               (swap! check-calls conj (vec args)))
                               lsp.diag/report-no-checker!   (fn [cid p]
                                                               (swap! no-checker conj
                                                                      {:client-id cid :path p}))]
                   (sut/check-root-form! client-id path 7 {})
                   (assertions
                    "Does NOT dispatch to daemon.check/check-root-form! when no checker matches"
                    @check-calls => []

                    "Reports no-checker exactly once"
                    (count @no-checker) => 1

                    "Forwards the LSP client-id to report-no-checker!"
                    (:client-id (first @no-checker)) => client-id

                    "Forwards the editor-supplied path to report-no-checker!"
                    (:path (first @no-checker)) => path))))

;; ============================================================================
;; check-file! - selects the matching checker when several are registered
;; ============================================================================

(specification "check-file! - selects the checker whose :project-dir is a prefix of the path"
               (let [check-calls (atom [])
                     no-checker  (atom [])
                     client-id   #uuid "00000000-0000-0000-0000-0000000000ee"]
                 (with-redefs [cp.conn/registered-checkers (atom {:checker-A {:project-dir  "/projA"
                                                                              :checker-type :clj}
                                                                  :checker-B {:project-dir  "/projB"
                                                                              :checker-type :cljs}})
                               daemon.check/check-file!    (fn [ws checker-cid p o]
                                                             (swap! check-calls conj checker-cid))
                               lsp.diag/report-no-checker! (fn [cid p]
                                                             (swap! no-checker conj p))]
                   (sut/check-file! client-id "/projB/src/x.cljs" {})
                   (assertions
                    "Routes /projB/... to :checker-B"
                    @check-calls => [:checker-B]

                    "Does not report no-checker when a registered checker matches"
                    @no-checker => []))))

;; ============================================================================
;; REGRESSION: path traversal / project-dir escape rejection
;;
;; This specification is EXPECTED TO FAIL until Phase 4 is implemented.
;; P4 fix needed: canonicalize and assert under :project-dir in
;; daemon/lsp/commands.clj.
;;
;; The current implementation in commands.clj only consults
;; cp.conn/get-checker-for, which is a naive `(.startsWith path project-dir)`
;; check. A path like "/proj/../etc/passwd" starts with "/proj" and so will
;; resolve to the registered checker, even though it walks OUT of the project
;; on disk. The fix is to canonicalize the editor-supplied path and assert
;; it is contained within :project-dir before dispatching.
;; ============================================================================

(specification "check-file! - rejects paths that escape :project-dir (P2B regression, expected to fail until P4)"
               (let [check-calls (atom [])
                     no-checker  (atom [])
                     client-id   #uuid "00000000-0000-0000-0000-0000000000ff"]
                 (with-redefs [cp.conn/registered-checkers (atom {:checker-1 {:project-dir  "/proj"
                                                                              :checker-type :clj}})
                               daemon.check/check-file!    (fn [ws checker-cid p o]
                                                             (swap! check-calls conj p))
                               lsp.diag/report-no-checker! (fn [cid p]
                                                             (swap! no-checker conj p))]
      ;; Path uses .. to walk out of /proj
                   (sut/check-file! client-id "/proj/../etc/passwd" {})
      ;; Absolute path entirely outside /proj
                   (sut/check-file! client-id "/etc/passwd" {})
                   (assertions
                    "Does NOT forward a `..`-escaping path to daemon.check/check-file!"
                    (some #{"/proj/../etc/passwd"} @check-calls) => nil

                    "Does NOT forward an absolute path outside :project-dir to daemon.check/check-file!"
                    (some #{"/etc/passwd"} @check-calls) => nil))))

(specification "check-root-form! - rejects paths that escape :project-dir (P2B regression, expected to fail until P4)"
               (let [check-calls (atom [])
                     no-checker  (atom [])
                     client-id   #uuid "00000000-0000-0000-0000-000000000111"]
                 (with-redefs [cp.conn/registered-checkers   (atom {:checker-1 {:project-dir  "/proj"
                                                                                :checker-type :clj}})
                               daemon.check/check-root-form! (fn [ws checker-cid p l o]
                                                               (swap! check-calls conj p))
                               lsp.diag/report-no-checker!   (fn [cid p]
                                                               (swap! no-checker conj p))]
                   (sut/check-root-form! client-id "/proj/../etc/passwd" 1 {})
                   (sut/check-root-form! client-id "/etc/passwd" 1 {})
                   (assertions
                    "Does NOT forward a `..`-escaping path to daemon.check/check-root-form!"
                    (some #{"/proj/../etc/passwd"} @check-calls) => nil

                    "Does NOT forward an absolute path outside :project-dir to daemon.check/check-root-form!"
                    (some #{"/etc/passwd"} @check-calls) => nil))))
