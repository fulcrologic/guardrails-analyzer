(ns com.fulcrologic.guardrails-analyzer.daemon.server.pathom-spec
  "Behavioral tests for the daemon pathom resolvers/mutations and parser plumbing.

   The pathom file wires up a small set of resolvers and mutations that mediate
   between checkers (which produce analysis output) and viewers (IDE/LSP/web UI).
   These tests pin the routing/composition behavior of each resolver/mutation
   without spinning up an actual Pathom parser, websocket server, or LSP runtime.

   NOTE: testing of the `report-analytics` mutation is intentionally SKIPPED
   per task #15 — that path is commercialization-only and slated for removal."
  (:require
   [com.fulcrologic.guardrails-analyzer.daemon.lsp.diagnostics :as lsp.diag]
   [com.fulcrologic.guardrails-analyzer.daemon.server.bindings :as bindings]
   [com.fulcrologic.guardrails-analyzer.daemon.server.checkers :as daemon.check]
   [com.fulcrologic.guardrails-analyzer.daemon.server.connection-management :as cp.conn]
   [com.fulcrologic.guardrails-analyzer.daemon.server.pathom :as sut]
   [com.fulcrologic.guardrails-analyzer.daemon.server.problems :as problems]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [com.fulcrologicpro.fulcro.networking.websocket-protocols :as wsp]
   [com.wsscode.pathom.connect :as pc]
   [com.wsscode.pathom.core :as p]
   [fulcro-spec.core :refer [=> assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

;; ============================================================================
;; Test helpers
;; ============================================================================

(defn fake-ws
  "Returns a WSNet stand-in that records every push into `pushes!` (an atom on a vector)."
  [pushes!]
  (reify wsp/WSNet
    (add-listener [_ _] nil)
    (remove-listener [_ _] nil)
    (push [_ cid verb edn]
      (swap! pushes! conj {:cid cid :verb verb :edn edn}))))

(defn invoke-resolver
  "Invoke a pc/defresolver-defined var by extracting its ::pc/resolve fn and calling it."
  [resolver-var env input]
  ((::pc/resolve resolver-var) env input))

(defn invoke-mutation
  "Invoke a pc/defmutation-defined var by extracting its ::pc/mutate fn and calling it."
  [mutation-var env input]
  ((::pc/mutate mutation-var) env input))

;; ============================================================================
;; all-problems resolver
;; ============================================================================

(specification "all-problems resolver"
               (component "returns problems for the checker that matches the calling viewer"
                          (let [viewer-cid    "v-1"
                                checker-cid   "c-1"
                                fake-problems [{:id 1} {:id 2}]
                                got-cids      (atom {})]
                            (with-redefs [cp.conn/viewer->checker (fn [vcid]
                                                                    (swap! got-cids assoc :viewer->checker vcid)
                                                                    checker-cid)
                                          problems/get!           (fn [cid]
                                                                    (swap! got-cids assoc :problems-get cid)
                                                                    fake-problems)]
                              (let [result (invoke-resolver sut/all-problems
                                                            {:cid viewer-cid}
                                                            {})]
                                (assertions
                                 "returns a single-key map keyed by :all-problems"
                                 (keys result) => [:all-problems]

                                 "the :all-problems value comes from problems/get! for the resolved checker"
                                 (:all-problems result) => fake-problems

                                 "viewer->checker is called with the viewer cid from the env :cid"
                                 (:viewer->checker @got-cids) => viewer-cid

                                 "problems/get! is called with the checker cid resolved from the viewer cid"
                                 (:problems-get @got-cids) => checker-cid))))))

;; ============================================================================
;; report-error mutation
;; ============================================================================

(specification "report-error mutation"
               (let [checker-cid       "c-1"
                     ws                ::stub-ws
                     error             "boom"
                     checker-info      {:project-dir "/proj"}
                     registered        (atom {checker-cid checker-info})
                     cp-conn-calls     (atom [])
                     lsp-calls         (atom [])]
                 (with-redefs [cp.conn/registered-checkers registered
                               cp.conn/report-error!       (fn [websockets cid err]
                                                             (swap! cp-conn-calls conj
                                                                    {:ws websockets :cid cid :error err}))
                               lsp.diag/report-error!      (fn [info err]
                                                             (swap! lsp-calls conj
                                                                    {:info info :error err}))]
                   (let [result (invoke-mutation sut/report-error
                                                 {:cid checker-cid :websockets ws}
                                                 {:error error})]
                     (assertions
                      "returns an empty map"
                      result => {}

                      "delegates to cp.conn/report-error! with the websockets handle from the env"
                      (:ws (first @cp-conn-calls)) => ws

                      "passes the checker cid (env :cid) through to cp.conn/report-error!"
                      (:cid (first @cp-conn-calls)) => checker-cid

                      "passes the input :error through to cp.conn/report-error!"
                      (:error (first @cp-conn-calls)) => error

                      "looks up the checker-info from registered-checkers and forwards it to lsp.diag/report-error!"
                      (:info (first @lsp-calls)) => checker-info

                      "passes the input :error through to lsp.diag/report-error!"
                      (:error (first @lsp-calls)) => error)))))

;; ============================================================================
;; report-analysis mutation
;; ============================================================================

(specification "report-analysis mutation"
               (let [checker-cid    "c-1"
                     ws             ::stub-ws
                     new-problems   [{:p 1}]
                     new-bindings   [{:b 1}]
                     checker-info   {:project-dir "/proj"}
                     registered     (atom {checker-cid checker-info})
                     problems-set   (atom nil)
                     bindings-set   (atom nil)
                     update-viewers (atom nil)
                     lsp-update     (atom nil)]
                 (with-redefs [cp.conn/registered-checkers registered
                               problems/set!               (fn [cid ps] (reset! problems-set [cid ps]))
                               bindings/set!               (fn [cid bs] (reset! bindings-set [cid bs]))
                               cp.conn/update-viewers-for! (fn [websockets cid] (reset! update-viewers [websockets cid]))
                               lsp.diag/update-problems!   (fn [info ps] (reset! lsp-update [info ps]))]
                   (let [result (invoke-mutation sut/report-analysis
                                                 {:cid checker-cid :websockets ws}
                                                 {:bindings new-bindings :problems new-problems})]
                     (assertions
                      "returns an empty map"
                      result => {}

                      "stores the input :problems against the checker cid via problems/set!"
                      @problems-set => [checker-cid new-problems]

                      "stores the input :bindings against the checker cid via bindings/set!"
                      @bindings-set => [checker-cid new-bindings]

                      "notifies viewers for the checker by calling cp.conn/update-viewers-for! with the websockets and checker cid"
                      @update-viewers => [ws checker-cid]

                      "forwards the resolved checker-info and the new problems to lsp.diag/update-problems!"
                      @lsp-update => [checker-info new-problems])))))

;; ============================================================================
;; subscribe mutation
;; ============================================================================

(specification "subscribe mutation"
               (let [viewer-cid     "v-1"
                     viewer-info    {:viewer-type :IDEA :project-dir "/proj"}
                     ws             ::stub-ws
                     viewers-atom   (atom {})
                     update-call    (atom nil)]
                 (with-redefs [cp.conn/subscribed-viewers viewers-atom
                               cp.conn/update-viewer!     (fn [websockets vcid info]
                                                            (reset! update-call [websockets vcid info]))]
                   (let [result (invoke-mutation sut/subscribe
                                                 {:cid viewer-cid :websockets ws}
                                                 viewer-info)]
                     (assertions
                      "returns an empty map"
                      result => {}

                      "registers the viewer-info under the viewer cid in cp.conn/subscribed-viewers"
                      (get @viewers-atom viewer-cid) => viewer-info

                      "calls cp.conn/update-viewer! with the websockets handle from the env"
                      (first @update-call) => ws

                      "calls cp.conn/update-viewer! with the viewer cid from the env :cid"
                      (second @update-call) => viewer-cid

                      "calls cp.conn/update-viewer! with the input viewer-info"
                      (nth @update-call 2) => viewer-info)))))

;; ============================================================================
;; register-checker mutation
;; ============================================================================

(specification "register-checker mutation"
               (let [checker-cid   "c-1"
                     checker-info  {:project-dir "/proj" :checker-type :clj}
                     checkers-atom (atom {})]
                 (with-redefs [cp.conn/registered-checkers checkers-atom]
                   (let [result (invoke-mutation sut/register-checker
                                                 {:cid checker-cid}
                                                 checker-info)]
                     (assertions
                      "returns an empty map"
                      result => {}

                      "registers the checker-info under the checker cid in cp.conn/registered-checkers"
                      (get @checkers-atom checker-cid) => checker-info)))))

;; ============================================================================
;; check-current-file mutation
;; ============================================================================

(specification "check-current-file mutation"
               (component "when a checker exists for the calling viewer"
                          (let [viewer-cid   "v-1"
                                checker-cid  "c-1"
                                ws           ::stub-ws
                                file         "/proj/src/foo.clj"
                                opts         {:refresh? true}
                                check-call   (atom nil)
                                no-checker   (atom nil)]
                            (with-redefs [cp.conn/viewer->checker     (fn [vcid] (when (= vcid viewer-cid) checker-cid))
                                          daemon.check/check-file!    (fn [websockets cid f o]
                                                                        (reset! check-call [websockets cid f o])
                                                                        :checked)
                                          cp.conn/report-no-checker!  (fn [& args]
                                                                        (reset! no-checker args)
                                                                        :no-checker)]
                              (let [result (invoke-mutation sut/check-current-file
                                                            {:cid viewer-cid :websockets ws}
                                                            {:file file :opts opts})]
                                (assertions
                                 "returns the result of daemon.check/check-file! (delegates the check)"
                                 result => :checked

                                 "delegates with the websockets handle from the env"
                                 (first @check-call) => ws

                                 "delegates with the checker cid resolved from the viewer cid"
                                 (second @check-call) => checker-cid

                                 "delegates with the input :file"
                                 (nth @check-call 2) => file

                                 "delegates with the input :opts"
                                 (nth @check-call 3) => opts

                                 "does NOT call cp.conn/report-no-checker! when a checker is found"
                                 @no-checker => nil)))))

               (component "when NO checker exists for the calling viewer"
                          (let [viewer-cid   "v-1"
                                ws           ::stub-ws
                                file         "/proj/src/foo.clj"
                                opts         {:refresh? false}
                                check-call   (atom nil)
                                no-checker   (atom nil)]
                            (with-redefs [cp.conn/viewer->checker     (fn [_] nil)
                                          daemon.check/check-file!    (fn [& args]
                                                                        (reset! check-call args)
                                                                        :checked)
                                          cp.conn/report-no-checker!  (fn [websockets vcid f]
                                                                        (reset! no-checker [websockets vcid f])
                                                                        :no-checker)]
                              (let [result (invoke-mutation sut/check-current-file
                                                            {:cid viewer-cid :websockets ws}
                                                            {:file file :opts opts})]
                                (assertions
                                 "returns the result of cp.conn/report-no-checker! (delegates the no-checker fallback)"
                                 result => :no-checker

                                 "calls cp.conn/report-no-checker! with the websockets handle from the env"
                                 (first @no-checker) => ws

                                 "calls cp.conn/report-no-checker! with the viewer cid from the env :cid"
                                 (second @no-checker) => viewer-cid

                                 "calls cp.conn/report-no-checker! with the input :file"
                                 (nth @no-checker 2) => file

                                 "does NOT call daemon.check/check-file! when no checker is found"
                                 @check-call => nil))))))

;; ============================================================================
;; check-root-form mutation
;; ============================================================================

(specification "check-root-form mutation"
               (component "when a checker exists for the calling viewer"
                          (let [viewer-cid    "v-1"
                                checker-cid   "c-1"
                                ws            ::stub-ws
                                file          "/proj/src/foo.clj"
                                line          42
                                opts          {:refresh? false}
                                check-call    (atom nil)
                                no-checker    (atom nil)]
                            (with-redefs [cp.conn/viewer->checker        (fn [vcid] (when (= vcid viewer-cid) checker-cid))
                                          daemon.check/check-root-form!  (fn [websockets cid f l o]
                                                                           (reset! check-call [websockets cid f l o])
                                                                           :checked)
                                          cp.conn/report-no-checker!     (fn [& args]
                                                                           (reset! no-checker args)
                                                                           :no-checker)]
                              (let [result (invoke-mutation sut/check-root-form
                                                            {:cid viewer-cid :websockets ws}
                                                            {:file file :line line :opts opts})]
                                (assertions
                                 "returns the result of daemon.check/check-root-form! (delegates the check)"
                                 result => :checked

                                 "delegates with the websockets handle from the env"
                                 (first @check-call) => ws

                                 "delegates with the checker cid resolved from the viewer cid"
                                 (second @check-call) => checker-cid

                                 "delegates with the input :file"
                                 (nth @check-call 2) => file

                                 "delegates with the input :line"
                                 (nth @check-call 3) => line

                                 "delegates with the input :opts"
                                 (nth @check-call 4) => opts

                                 "does NOT call cp.conn/report-no-checker! when a checker is found"
                                 @no-checker => nil)))))

               (component "when NO checker exists for the calling viewer"
                          (let [viewer-cid   "v-1"
                                ws           ::stub-ws
                                file         "/proj/src/foo.clj"
                                line         42
                                opts         {:refresh? true}
                                check-call   (atom nil)
                                no-checker   (atom nil)]
                            (with-redefs [cp.conn/viewer->checker        (fn [_] nil)
                                          daemon.check/check-root-form!  (fn [& args]
                                                                           (reset! check-call args)
                                                                           :checked)
                                          cp.conn/report-no-checker!     (fn [websockets vcid f]
                                                                           (reset! no-checker [websockets vcid f])
                                                                           :no-checker)]
                              (let [result (invoke-mutation sut/check-root-form
                                                            {:cid viewer-cid :websockets ws}
                                                            {:file file :line line :opts opts})]
                                (assertions
                                 "returns the result of cp.conn/report-no-checker! (delegates the no-checker fallback)"
                                 result => :no-checker

                                 "calls cp.conn/report-no-checker! with the websockets handle from the env"
                                 (first @no-checker) => ws

                                 "calls cp.conn/report-no-checker! with the viewer cid from the env :cid"
                                 (second @no-checker) => viewer-cid

                                 "calls cp.conn/report-no-checker! with the input :file (NOT the line)"
                                 (nth @no-checker 2) => file

                                 "does NOT call daemon.check/check-root-form! when no checker is found"
                                 @check-call => nil))))))

;; ============================================================================
;; all-resolvers vector
;; ============================================================================

(specification "all-resolvers vector"
               (let [registered (set sut/all-resolvers)]
                 (assertions
                  "registers the all-problems resolver"
                  (contains? registered sut/all-problems) => true

                  "registers the report-analysis mutation"
                  (contains? registered sut/report-analysis) => true

                  "registers the report-error mutation"
                  (contains? registered sut/report-error) => true

                  "registers the subscribe mutation"
                  (contains? registered sut/subscribe) => true

                  "registers the register-checker mutation"
                  (contains? registered sut/register-checker) => true

                  "registers the check-current-file mutation"
                  (contains? registered sut/check-current-file) => true

                  "registers the check-root-form mutation"
                  (contains? registered sut/check-root-form) => true

                  "registers the report-analytics mutation (presence pinned; behavior intentionally NOT tested per task #15)"
                  (contains? registered sut/report-analytics) => true

                  "exposes exactly the eight known resolvers/mutations"
                  (count sut/all-resolvers) => 8)))

;; ============================================================================
;; preprocess-parser-plugin
;; ============================================================================

(specification "preprocess-parser-plugin"
               (component "the produced plugin map shape"
                          (let [plugin (sut/preprocess-parser-plugin identity)]
                            (assertions
                             "is a map containing exactly the ::p/wrap-parser key"
                             (keys plugin) => [::p/wrap-parser]

                             "the ::p/wrap-parser value is a function (ready for pathom to install)"
                             (fn? (::p/wrap-parser plugin)) => true)))

               (component "preprocessor invocation and parser delegation"
                          (let [pre-calls   (atom [])
                                parser-call (atom nil)
                                pre-fn      (fn [{:keys [env tx] :as input}]
                                              (swap! pre-calls conj input)
                        ;; preprocessor is allowed to mutate env / tx
                                              {:env (assoc env :preprocessed? true)
                                               :tx  (conj tx :extra)})
                                inner       (fn [env tx]
                                              (reset! parser-call {:env env :tx tx})
                                              :parsed)
                                wrap        (::p/wrap-parser (sut/preprocess-parser-plugin pre-fn))
                                wrapped     (wrap inner)
                                result      (wrapped {:base true} [:thing])]
                            (assertions
                             "wrap-parser returns a function (suitable as a parser)"
                             (fn? wrapped) => true

                             "the preprocess fn is called with a map containing :env and :tx"
                             (first @pre-calls) => {:env {:base true} :tx [:thing]}

                             "when preprocessing succeeds, the inner parser receives the preprocessed env"
                             (:env @parser-call) => {:base true :preprocessed? true}

                             "when preprocessing succeeds, the inner parser receives the preprocessed tx"
                             (:tx @parser-call) => [:thing :extra]

                             "wrapped parser returns whatever the inner parser returned"
                             result => :parsed)))

               (component "guard: preprocess returns a non-map env"
                          (let [parser-called? (atom false)
                                pre-fn         (fn [_] {:env nil :tx [:x]}) ; nil is not a map
                                inner          (fn [_ _] (reset! parser-called? true) :should-not-happen)
                                wrap           (::p/wrap-parser (sut/preprocess-parser-plugin pre-fn))
                                wrapped        (wrap inner)
                                result         (wrapped {} [:x])]
                            (assertions
                             "returns an empty map without calling the inner parser"
                             result => {}

                             "the inner parser is NOT invoked when env is non-map"
                             @parser-called? => false)))

               (component "guard: preprocess returns an empty tx"
                          (let [parser-called? (atom false)
                                pre-fn         (fn [_] {:env {} :tx []})
                                inner          (fn [_ _] (reset! parser-called? true) :should-not-happen)
                                wrap           (::p/wrap-parser (sut/preprocess-parser-plugin pre-fn))
                                wrapped        (wrap inner)
                                result         (wrapped {} [:x])]
                            (assertions
                             "returns an empty map without calling the inner parser"
                             result => {}

                             "the inner parser is NOT invoked when tx is empty"
                             @parser-called? => false))))

;; ============================================================================
;; log-requests
;; ============================================================================

(specification "log-requests"
               (let [req {:env {:cid "v-1"} :tx [:hello]}]
                 (assertions
                  "returns the request unchanged (it is a logging passthrough)"
                  (sut/log-requests req) => req

                  "preserves the :env key"
                  (:env (sut/log-requests req)) => {:cid "v-1"}

                  "preserves the :tx key"
                  (:tx (sut/log-requests req)) => [:hello]

                  "passes through nil-tx requests unchanged"
                  (sut/log-requests {:env {} :tx nil}) => {:env {} :tx nil})))

;; ============================================================================
;; log-error
;; ============================================================================

(specification "log-error"
               (let [captured (atom nil)
                     e        (ex-info "boom" {:k :v})]
                 (with-redefs [p/error-str (fn [err] (reset! captured err) "ERR_STR")]
                   (let [result (sut/log-error {} e)]
                     (assertions
                      "returns the result of pathom's p/error-str (the formatted error string)"
                      result => "ERR_STR"

                      "passes the original exception through to p/error-str"
                      @captured => e)))))

;; ============================================================================
;; build-parser
;; ============================================================================

(specification "build-parser"
               (component "produces a callable parser fn"
                          (let [load-config-calls (atom 0)
                                captured-config   (atom nil)
                                captured-plugins  (atom nil)]
                            (with-redefs [com.fulcrologic.guardrails-analyzer.dot-config/load-config!
                                          (fn []
                                            (swap! load-config-calls inc)
                                            {:fake :config})
                                          p/parser
                                          (fn [config]
                                            (reset! captured-config config)
                                            (reset! captured-plugins (::p/plugins config))
                                            (fn fake-real-parser [env tx] {:got [env tx]}))]
                              (let [parser (sut/build-parser)]
                                (assertions
                                 "returns a function (the wrapped parser)"
                                 (fn? parser) => true

                                 "loads the dot-config exactly once during construction"
                                 @load-config-calls => 1

                                 "the constructed parser config places :dot-config under ::p/env"
                                 (get-in @captured-config [::p/env :dot-config]) => {:fake :config}

                                 "the constructed parser config exposes a vector of plugins"
                                 (vector? @captured-plugins) => true

                                 "uses log-error as the ::p/process-error handler"
                                 (get-in @captured-config [::p/env ::p/process-error]) => sut/log-error

                                 "uses pc/mutate as the ::p/mutate dispatcher"
                                 (::p/mutate @captured-config) => pc/mutate)))))

               (component "wrapping behavior: trace? toggle"
                          (let [seen-tx (atom nil)]
                            (with-redefs [com.fulcrologic.guardrails-analyzer.dot-config/load-config!
                                          (constantly {})
                                          p/parser
                                          (fn [_]
                                            (fn [_env tx]
                                              (reset! seen-tx tx)
                                              :ok))
                    ;; Force trace? to false by ensuring the JVM property is absent
                                          ]
        ;; Default state: no -Dtrace property, so trace? should be false
                              (System/clearProperty "trace")
                              (let [parser (sut/build-parser)
                                    _      (parser {} [:foo])
                                    tx-no-trace @seen-tx]
                                (assertions
                                 "without -Dtrace set, the wrapped parser does NOT append the pathom trace key to the tx"
                                 tx-no-trace => [:foo]

                                 "the trace marker keyword is absent from the forwarded tx"
                                 (some #{:com.wsscode.pathom/trace} tx-no-trace) => nil))

        ;; Now flip the trace flag and rebuild
                              (System/setProperty "trace" "1")
                              (try
                                (let [parser-trace (sut/build-parser)
                                      _            (parser-trace {} [:foo])
                                      tx-trace     @seen-tx]
                                  (assertions
                                   "with -Dtrace set, the wrapped parser appends the pathom trace key to the tx"
                                   (some #{:com.wsscode.pathom/trace} tx-trace) => :com.wsscode.pathom/trace

                                   "the original tx items are preserved when tracing is enabled"
                                   (some #{:foo} tx-trace) => :foo))
                                (finally
                                  (System/clearProperty "trace")))))))
