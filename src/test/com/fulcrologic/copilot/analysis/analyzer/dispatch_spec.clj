(ns com.fulcrologic.copilot.analysis.analyzer.dispatch-spec
  (:require
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as cp.ana.disp]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [fulcro-spec.core :refer [assertions specification when-mocking]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defmethod cp.art/cljc-rewrite-sym-ns-mm "analyzer-spec" [sym] "cljc-analyzer-spec")

(defmethod cp.ana.disp/analyze-mm 'custom [& _] {})
(defmethod cp.ana.disp/analyze-mm 'nsed/custom [& _] {})
(defmethod cp.ana.disp/analyze-mm 'cljc-analyzer-spec/custom [& _] {})

(specification "analyze-dispatch"
  (when-mocking
    (cp.art/function-detail _ sym) => (case sym (local.defn) true false)
    (cp.art/external-function-detail _ sym) => (case sym ext.fn true false)
    (cp.art/symbol-detail _ sym) => (case sym (local.sym) true false)
    (cp.art/qualify-extern _ sym) => (case sym when-not 'clojure.core/when-not
                                               (cp.art/cljc-rewrite-sym-ns sym))
    (let [env  (tf/test-env)
          disp #(cp.ana.disp/analyze-dispatch env %)]
      (assertions
        (disp '(local.defn :a)) => :function/call
        (disp '(ext.fn :a)) => :function.external/call
        (disp '(local.sym)) => :symbol.local/call
        (disp '(custom :a)) => 'custom
        (disp '(if true :a :b)) => 'clojure.core/if
        (disp 'local.sym) => :symbol.local/lookup
        (disp '(when-let [a 1] :a :b)) => 'clojure.core/when-let
        (disp '(when-not false :a)) => 'clojure.core/when-not
        (disp '(nsed/custom :a)) => 'nsed/custom
        (disp '(analyzer-spec/custom :a)) => 'cljc-analyzer-spec/custom
        (disp '((any) :a)) => :function.expression/call
        (disp '(:ifn {})) => :ifn/call
        (disp '('ifn {})) => :ifn/call
        (disp '({} :a)) => :ifn/call
        (disp '(unk {})) => :unknown
        (disp '(##NaN :a)) => :unknown))))
