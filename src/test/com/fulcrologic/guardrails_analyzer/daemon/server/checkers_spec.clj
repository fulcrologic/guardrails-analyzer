(ns com.fulcrologic.guardrails-analyzer.daemon.server.checkers-spec
  "Behavior coverage for the daemon's checker dispatch namespace.

  Targets src/daemon/.../daemon/server/checkers.clj lines 20-41:
  - check-file!
  - root-form-at?
  - check-root-form!

  Includes a P2B regression test (un-skipped, expected to FAIL until P4)
  asserting that an editor-supplied path containing `..` or an absolute path
  outside the registered :project-dir is REJECTED before being passed to the
  reader.

  P4 fix needed: canonicalize and assert under :project-dir in
  daemon/server/checkers.clj and daemon/lsp/commands.clj."
  (:require
   [com.fulcrologic.guardrails-analyzer.daemon.server.checkers :as sut]
   [com.fulcrologic.guardrails-analyzer.daemon.server.connection-management :as cp.conn]
   [com.fulcrologic.guardrails-analyzer.reader :as cp.reader]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [com.fulcrologicpro.fulcro.networking.websocket-protocols :as wsp]
   [fulcro-spec.core :refer [assertions specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn fake-ws
  "Returns a stand-in WSNet whose push! invocations are appended to `recorded` as
  [cid verb edn] vectors."
  [recorded]
  (reify wsp/WSNet
    (add-listener [_ _])
    (remove-listener [_ _])
    (push [_ cid verb edn]
      (swap! recorded conj [cid verb edn])
      nil)))

;; ============================================================================
;; opts->check-type
;; ============================================================================

(specification "opts->check-type"
               (assertions
                "Returns :refresh-and-check! when :refresh? is true"
                (sut/opts->check-type {:refresh? true})
                => :refresh-and-check!

                "Returns :check! when :refresh? is false"
                (sut/opts->check-type {:refresh? false})
                => :check!

                "Returns :check! when :refresh? is missing"
                (sut/opts->check-type {})
                => :check!

                "Returns :check! when :refresh? is nil"
                (sut/opts->check-type {:refresh? nil})
                => :check!))

;; ============================================================================
;; root-form-at?
;; ============================================================================

(specification "root-form-at?"
               (let [form (with-meta '() {:line 5 :end-line 10})]
                 (assertions
                  "Returns true when cursor is between :line and :end-line"
                  (sut/root-form-at? 7 form)
                  => true

                  "Returns true at the start boundary (cursor = :line)"
                  (sut/root-form-at? 5 form)
                  => true

                  "Returns true at the end boundary (cursor = :end-line)"
                  (sut/root-form-at? 10 form)
                  => true

                  "Returns false when cursor is above the form"
                  (sut/root-form-at? 4 form)
                  => false

                  "Returns false when cursor is below the form"
                  (sut/root-form-at? 11 form)
                  => false)))

;; ============================================================================
;; check-file! - normal path success
;; ============================================================================

(specification "check-file! - success on a normal path"
               (let [recorded   (atom [])
                     read-calls (atom [])
                     ws         (fake-ws recorded)
                     path       "/proj/src/foo.clj"]
                 (with-redefs [cp.conn/registered-checkers (atom {:checker-1 {:project-dir  "/proj"
                                                                              :checker-type :clj}})
                               cp.reader/read-file         (fn [p ct]
                                                             (swap! read-calls conj [p ct])
                                                             {:file p :NS "foo" :forms []})]
                   (sut/check-file! ws :checker-1 path {:refresh? false})
                   (assertions
                    "Reads the requested file once via the reader"
                    (count @read-calls) => 1

                    "Passes the editor-supplied path to the reader"
                    (ffirst @read-calls) => path

                    "Passes the registered checker-type as the read mode"
                    (second (first @read-calls)) => :clj

                    "Pushes a single notification to the websocket"
                    (count @recorded) => 1

                    "Routes the notification to the registered checker-cid"
                    (get-in @recorded [0 0]) => :checker-1

                    "Sends the :check! verb when :refresh? is false"
                    (get-in @recorded [0 1]) => :check!

                    "Includes the file path in the pushed payload"
                    (get-in @recorded [0 2 :file]) => path

                    "Tags the payload as a [:check! :file] command"
                    (get-in @recorded [0 2 :check-command-type]) => [:check! :file]))))

(specification "check-file! - refresh option"
               (let [recorded   (atom [])
                     read-calls (atom [])
                     ws         (fake-ws recorded)]
                 (with-redefs [cp.conn/registered-checkers (atom {:checker-1 {:project-dir  "/proj"
                                                                              :checker-type :clj}})
                               cp.reader/read-file         (fn [p ct]
                                                             (swap! read-calls conj [p ct])
                                                             {:file p :forms []})]
                   (sut/check-file! ws :checker-1 "/proj/src/foo.clj" {:refresh? true})
                   (assertions
                    "Sends the :refresh-and-check! verb when :refresh? is true"
                    (get-in @recorded [0 1]) => :refresh-and-check!

                    "Tags the payload as a [:refresh-and-check! :file] command"
                    (get-in @recorded [0 2 :check-command-type]) => [:refresh-and-check! :file]))))

(specification "check-file! - no push when checker-cid is unknown"
               (let [recorded   (atom [])
                     read-calls (atom [])
                     ws         (fake-ws recorded)]
                 (with-redefs [cp.conn/registered-checkers (atom {})
                               cp.reader/read-file         (fn [p ct]
                                                             (swap! read-calls conj [p ct])
                                                             {:file p :forms []})]
                   (sut/check-file! ws :unknown-checker "/proj/src/foo.clj" {})
                   (assertions
                    "Does not invoke the reader when no checker is registered for the cid"
                    @read-calls => []

                    "Does not push any notification when no checker is registered for the cid"
                    @recorded => []))))

;; ============================================================================
;; check-root-form! - normal path success
;; ============================================================================

(specification "check-root-form! - success on a normal path"
               (let [recorded   (atom [])
                     read-calls (atom [])
                     ws         (fake-ws recorded)
                     path       "/proj/src/foo.clj"
        ;; Three top-level forms with non-overlapping line ranges.
                     form1      (with-meta '(do :a) {:line 1  :end-line 5})
                     form2      (with-meta '(do :b) {:line 6  :end-line 10})
                     form3      (with-meta '(do :c) {:line 11 :end-line 15})]
                 (with-redefs [cp.conn/registered-checkers (atom {:checker-1 {:project-dir  "/proj"
                                                                              :checker-type :clj}})
                               cp.reader/read-file         (fn [p ct]
                                                             (swap! read-calls conj [p ct])
                                                             {:file p :NS "foo" :forms [form1 form2 form3]})]
                   (sut/check-root-form! ws :checker-1 path 7 {:refresh? false})
                   (assertions
                    "Reads the requested file once via the reader"
                    (count @read-calls) => 1

                    "Passes the editor-supplied path to the reader"
                    (ffirst @read-calls) => path

                    "Passes the registered checker-type as the read mode"
                    (second (first @read-calls)) => :clj

                    "Pushes a single notification to the websocket"
                    (count @recorded) => 1

                    "Routes the notification to the registered checker-cid"
                    (get-in @recorded [0 0]) => :checker-1

                    "Sends the :check! verb when :refresh? is false"
                    (get-in @recorded [0 1]) => :check!

                    "Tags the payload as a [:check! :root-form] command"
                    (get-in @recorded [0 2 :check-command-type]) => [:check! :root-form]))))

(specification "check-root-form! - refresh option"
               (let [recorded   (atom [])
                     read-calls (atom [])
                     ws         (fake-ws recorded)]
                 (with-redefs [cp.conn/registered-checkers (atom {:checker-1 {:project-dir  "/proj"
                                                                              :checker-type :clj}})
                               cp.reader/read-file         (fn [p ct]
                                                             (swap! read-calls conj [p ct])
                                                             {:file p :forms []})]
                   (sut/check-root-form! ws :checker-1 "/proj/src/foo.clj" 1 {:refresh? true})
                   (assertions
                    "Sends the :refresh-and-check! verb when :refresh? is true"
                    (get-in @recorded [0 1]) => :refresh-and-check!

                    "Tags the payload as a [:refresh-and-check! :root-form] command"
                    (get-in @recorded [0 2 :check-command-type]) => [:refresh-and-check! :root-form]))))

(specification "check-root-form! - no push when checker-cid is unknown"
               (let [recorded   (atom [])
                     read-calls (atom [])
                     ws         (fake-ws recorded)]
                 (with-redefs [cp.conn/registered-checkers (atom {})
                               cp.reader/read-file         (fn [p ct]
                                                             (swap! read-calls conj [p ct])
                                                             {:file p :forms []})]
                   (sut/check-root-form! ws :unknown-checker "/proj/src/foo.clj" 1 {})
                   (assertions
                    "Does not invoke the reader when no checker is registered for the cid"
                    @read-calls => []

                    "Does not push any notification when no checker is registered for the cid"
                    @recorded => []))))

;; ============================================================================
;; REGRESSION: path traversal / project-dir escape rejection
;;
;; This specification is EXPECTED TO FAIL until Phase 4 is implemented.
;; P4 fix needed: canonicalize and assert under :project-dir in
;; daemon/server/checkers.clj and daemon/lsp/commands.clj.
;;
;; The current implementation forwards `path` straight to cp.reader/read-file
;; and pushes a websocket message, even when the path walks out of the
;; registered :project-dir via `..` or is an unrelated absolute path.
;; ============================================================================

(specification "check-file! - rejects paths that escape :project-dir (P2B regression, expected to fail until P4)"
               (let [recorded   (atom [])
                     read-calls (atom [])
                     ws         (fake-ws recorded)]
                 (with-redefs [cp.conn/registered-checkers (atom {:checker-1 {:project-dir  "/proj"
                                                                              :checker-type :clj}})
                               cp.reader/read-file         (fn [p ct]
                                                             (swap! read-calls conj p)
                                                             {:file p :forms []})]
      ;; Path uses .. to walk out of /proj
                   (sut/check-file! ws :checker-1 "/proj/../etc/passwd" {})
      ;; Absolute path entirely outside /proj
                   (sut/check-file! ws :checker-1 "/etc/passwd" {})
                   (assertions
                    "Does not invoke the reader for a `..`-escaping path"
                    (some #{"/proj/../etc/passwd"} @read-calls) => nil

                    "Does not invoke the reader for an absolute path outside :project-dir"
                    (some #{"/etc/passwd"} @read-calls) => nil

                    "Does not push any check notification for either rejected path"
                    @recorded => []))))

(specification "check-root-form! - rejects paths that escape :project-dir (P2B regression, expected to fail until P4)"
               (let [recorded   (atom [])
                     read-calls (atom [])
                     ws         (fake-ws recorded)]
                 (with-redefs [cp.conn/registered-checkers (atom {:checker-1 {:project-dir  "/proj"
                                                                              :checker-type :clj}})
                               cp.reader/read-file         (fn [p ct]
                                                             (swap! read-calls conj p)
                                                             {:file p :forms []})]
                   (sut/check-root-form! ws :checker-1 "/proj/../etc/passwd" 1 {})
                   (sut/check-root-form! ws :checker-1 "/etc/passwd" 1 {})
                   (assertions
                    "Does not invoke the reader for a `..`-escaping path"
                    (some #{"/proj/../etc/passwd"} @read-calls) => nil

                    "Does not invoke the reader for an absolute path outside :project-dir"
                    (some #{"/etc/passwd"} @read-calls) => nil

                    "Does not push any check notification for either rejected path"
                    @recorded => []))))
