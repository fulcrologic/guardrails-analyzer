(ns com.fulcrologic.guardrails-pro.analysis.analyzer-spec
  (:require
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :as grp.ana.disp]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [fulcro-spec.core :refer [specification assertions when-mocking]]))

(defmethod grp.art/cljc-rewrite-sym-ns-mm "analyzer-spec" [sym] "cljc-analyzer-spec")

(specification "analyze-dispatch"
  (when-mocking
    (grp.art/function-detail _ sym) => (case sym 'foo true false)
    (grp.art/external-function-detail _ sym) => (case sym 'get true false)
    (grp.art/symbol-detail _ sym) => (case sym 'local true false)
    (methods _) => (zipmap '[bar c/a cljc-analyzer-spec/a] (repeat true))
    (let [env (grp.art/build-env)]
      (assertions
        (grp.ana.disp/analyze-dispatch env '(foo :a)) => :function/call
        (grp.ana.disp/analyze-dispatch env '(get :a)) => :function.external/call
        (grp.ana.disp/analyze-dispatch env '(local))  => :symbol.local/lookup
        (grp.ana.disp/analyze-dispatch env '(bar :a)) => 'bar
        (grp.ana.disp/analyze-dispatch env '(c/a :a)) => 'c/a
        (grp.ana.disp/analyze-dispatch env '(analyzer-spec/a :a)) => 'cljc-analyzer-spec/a
        (grp.ana.disp/analyze-dispatch env '((q :b) :a)) => :function.expression/call
        (grp.ana.disp/analyze-dispatch env '(a {})) => :ifn/call
        (grp.ana.disp/analyze-dispatch env '({} :a)) => :ifn/call
        (grp.ana.disp/analyze-dispatch env '(##NaN :a)) => :unknown))))
