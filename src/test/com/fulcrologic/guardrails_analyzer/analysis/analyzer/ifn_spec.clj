(ns com.fulcrologic.guardrails-analyzer.analysis.analyzer.ifn-spec
  "Tests for the :ifn/call and :ifn/literal analyzers, which handle invocation
   of IFn-implementing values (keywords, sets, maps, vectors, quoted symbols)
   as well as bare IFn values used as data."
  (:require
   [com.fulcrologic.guardrails-analyzer.analysis.analyze-test-utils :as cp.atu]
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer.dispatch :as cp.ana.disp]
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer.ifn :as cp.ana.ifn]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions component specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn fresh-env! []
  (cp.art/clear-problems!)
  (cp.art/clear-bindings!)
  (tf/test-env))

(defn problem-types []
  (mapv ::cp.art/problem-type @cp.art/problems))

(specification ":ifn/literal returns the sexpr as both the only sample and the original expression"
  ;; The :ifn/literal defmethod fires from analyze-dispatch when an expression
  ;; is itself an IFn but is NOT a list, symbol, vector, set, map, or boolean.
  ;; A bare keyword used as a value is the canonical case.
               (component "for a bare keyword used as a value"
                          (let [env (fresh-env!)
                                td  (cp.ana.disp/-analyze! env :some/keyword)]
                            (assertions
                             "samples set contains exactly the keyword itself"
                             (::cp.art/samples td) => #{:some/keyword}
                             "original-expression is the keyword itself"
                             (::cp.art/original-expression td) => :some/keyword
                             "records no problems for a literal IFn reference"
                             (count @cp.art/problems) => 0))))

(specification ":ifn/call with a keyword head performs a keyword-as-fn lookup"
  ;; (:k m) reads the value of :k from m. The :ifn/call dispatch sees a
  ;; ::lit/keyword on the head's td and so calls analyze:ifn-call!, which
  ;; produces samples by applying the keyword as a fn to the map sample.
               (let [env (fresh-env!)
                     td  (cp.atu/analyze-string! env "(:foo {:foo 1})")]
                 (assertions
                  "samples set contains the value at :foo (1) — keyword-as-fn lookup"
                  (::cp.art/samples td) => #{1}
                  "records no info/failed-to-analyze-unknown-expression problem"
                  (some #{:info/failed-to-analyze-unknown-expression} (problem-types)) => nil)))

(specification ":ifn/call with a map head performs a map-as-fn lookup"
  ;; ({k v ...} k) returns the value associated with k. The :ifn/call dispatch
  ;; sees a ::lit/map on the head's td and so calls analyze:ifn-call! which
  ;; applies the map as a fn to the argument sample.
               (let [env (fresh-env!)
                     td  (cp.atu/analyze-string! env "({:a 1 :b 2} :a)")]
                 (assertions
                  "samples set contains the value at :a (1) — map-as-fn lookup"
                  (::cp.art/samples td) => #{1}
                  "records no info/failed-to-analyze-unknown-expression problem"
                  (some #{:info/failed-to-analyze-unknown-expression} (problem-types)) => nil)))

(specification ":ifn/call with a set head performs a set-as-fn membership check"
  ;; (#{a b} x) returns x if x is in the set, otherwise nil. The :ifn/call
  ;; dispatch sees a ::lit/set on the head's td and so calls analyze:ifn-call!.
               (component "argument IS a member of the set"
                          (let [env (fresh-env!)
                                td  (cp.atu/analyze-string! env "(#{:a :b} :a)")]
                            (assertions
                             "samples set contains the argument :a (member found)"
                             (::cp.art/samples td) => #{:a}
                             "records no info/failed-to-analyze-unknown-expression problem"
                             (some #{:info/failed-to-analyze-unknown-expression} (problem-types)) => nil)))

               (component "argument is NOT a member of the set"
                          (let [env (fresh-env!)
                                td  (cp.atu/analyze-string! env "(#{:a :b} :c)")]
                            (assertions
                             "samples set contains nil (membership returned nil for :c)"
                             (::cp.art/samples td) => #{nil}))))

(specification ":ifn/call with a vector head falls through to the unknown-expr path"
  ;; Although vectors are valid IFn (they support nth-style invocation), the
  ;; :ifn/call analyzer's case statement explicitly enumerates the supported
  ;; literal kinds (::lit/quoted-symbol, ::lit/keyword, ::lit/map, ::lit/set).
  ;; ::lit/vector is NOT in that list, so the analyzer records an
  ;; :info/failed-to-analyze-unknown-expression info problem instead of
  ;; producing samples from a vector-as-nth invocation.
               (let [env (fresh-env!)
                     _   (cp.atu/analyze-string! env "([10 20 30] 0)")]
                 (assertions
                  "records exactly an :info/failed-to-analyze-unknown-expression problem"
                  (some #{:info/failed-to-analyze-unknown-expression} (problem-types))
                  => :info/failed-to-analyze-unknown-expression)))

(specification ":ifn/call with a quoted-symbol head dispatches into analyze:ifn-call!"
  ;; ('foo {'foo 1}) is a legal IFn invocation in Clojure: a symbol applied to
  ;; a map looks the symbol up in the map. The quote analyzer assigns
  ;; ::lit/kind ::lit/quoted-symbol to the head's td, which IS in the
  ;; supported case, so analyze:ifn-call! is called and produces samples.
               (let [env (fresh-env!)
                     td  (cp.atu/analyze-string! env "('foo {'foo 1})")]
                 (assertions
                  "samples set contains the value at 'foo (1) — quoted-symbol-as-fn lookup"
                  (::cp.art/samples td) => #{1}
                  "records no info/failed-to-analyze-unknown-expression problem"
                  (some #{:info/failed-to-analyze-unknown-expression} (problem-types)) => nil)))

(specification ":ifn/call delegates to analyze:ifn-call! when the head td has no ::lit/kind"
  ;; In raw (unread) source — for example a quoted form passed in directly —
  ;; the head of a list like (:foo {:foo 42}) is a bare keyword and dispatches
  ;; through :ifn/literal, which produces a td WITHOUT ::lit/kind. The
  ;; :ifn/call analyzer must then take the no-kind branch (no if-let match)
  ;; and call analyze:ifn-call! anyway.
  ;;
  ;; NOTE: the raw literal `42` in the inner map does NOT flow through —
  ;; analyze-hashmap! substitutes ::cp.art/unknown for raw values (no
  ;; samples-producing dispatch for bare numbers). So when ifn-call! applies
  ;; :foo to the sampled map ({:foo ::cp.art/unknown}), the result is
  ;; ::cp.art/unknown. The behavior we are pinning down here is: the no-kind
  ;; branch DOES produce a sample at all (i.e. analyze:ifn-call! was invoked),
  ;; not what the literal value is.
               (let [env (fresh-env!)
                     td  (cp.ana.disp/-analyze! env '(:foo {:foo 42}))]
                 (assertions
                  "samples set contains ::cp.art/unknown — the no-kind branch still produced a sample (the raw literal 42 did not flow through)"
                  (::cp.art/samples td) => #{::cp.art/unknown}
                  "records info/failed-to-analyze-unknown-expression for the raw 42 literal that fell through dispatch"
                  (some #{:info/failed-to-analyze-unknown-expression} (problem-types)) => :info/failed-to-analyze-unknown-expression)))

(specification "analyze:ifn-call! produces samples by applying the ifn samples to the arg samples"
  ;; The function under test takes a td (whose samples must each be IFn) and a
  ;; seq of arg tds, and returns a td with samples drawn from applying each
  ;; sampled ifn to each sampled args tuple. We isolate it from dispatch by
  ;; calling it directly with hand-crafted tds.
               (component "single-keyword ifn applied to a single map argument"
                          (let [env     (fresh-env!)
                                ifn-td  {::cp.art/samples #{:foo}}
                                args-td [{::cp.art/samples #{{:foo 1 :bar 2}}}]
                                td      (cp.ana.ifn/analyze:ifn-call! env ifn-td args-td)]
                            (assertions
                             "result is a map with a samples key"
                             (contains? td ::cp.art/samples) => true
                             "samples set contains exactly 1 (the :foo lookup result)"
                             (::cp.art/samples td) => #{1})))

               (component "set-as-fn ifn applied to a single keyword argument"
                          (let [env     (fresh-env!)
                                ifn-td  {::cp.art/samples #{#{:a :b}}}
                                args-td [{::cp.art/samples #{:a}}]
                                td      (cp.ana.ifn/analyze:ifn-call! env ifn-td args-td)]
                            (assertions
                             "samples set contains :a (set-as-fn returns the arg when present)"
                             (::cp.art/samples td) => #{:a})))

               (component "map-as-fn ifn applied to a missing-key argument"
                          (let [env     (fresh-env!)
                                ifn-td  {::cp.art/samples #{{:a 1}}}
                                args-td [{::cp.art/samples #{:missing}}]
                                td      (cp.ana.ifn/analyze:ifn-call! env ifn-td args-td)]
                            (assertions
                             "samples set contains nil (map-as-fn returns nil for missing key)"
                             (::cp.art/samples td) => #{nil}))))
