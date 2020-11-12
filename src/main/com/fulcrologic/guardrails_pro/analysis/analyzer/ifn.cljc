(ns com.fulcrologic.guardrails-pro.analysis.analyzer.ifn
  (:require
    [clojure.test.check.generators :as gen]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :as grp.ana.disp]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.literals :as lit]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]))

(defn random-samples [env tds]
  (if (some ::grp.art/unknown-expression tds) #{}
    (->> tds
      (map (comp gen/elements ::grp.art/samples))
      (apply gen/tuple)
      (grp.spec/sample env)
      set)))

(defn analyze:ifn:literal-call!
  [env {ifn ::grp.art/original-expression} args-td]
  {::grp.art/samples (set (map (partial apply ifn)
                            (random-samples env args-td)))})

(defn analyze:ifn-call! [env ifn-td args-td]
  ;; TASK: non-literals & objects that implement ifn?
  ;; TASK: must implement ifn protocol
  (grp.ana.disp/unknown-expr env
    (::grp.art/original-expression ifn-td)))

(defmethod grp.ana.disp/analyze-mm :ifn/call [env [ifn & args :as sexpr]]
  (let [ifn-td (grp.ana.disp/-analyze! env ifn)
        args-td (map (partial grp.ana.disp/-analyze! env) args)]
    (if-let [ifn-kind (::lit/kind ifn-td)]
      (case ifn-kind
        (::lit/quoted-symbol ::lit/keyword ::lit/map ::lit/set)
        (analyze:ifn:literal-call! env ifn-td args-td)
        #_:else
        (grp.ana.disp/unknown-expr env sexpr))
      (analyze:ifn-call! env ifn-td args-td))))
