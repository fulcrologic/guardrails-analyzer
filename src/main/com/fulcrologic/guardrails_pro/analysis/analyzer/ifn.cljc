(ns com.fulcrologic.guardrails-pro.analysis.analyzer.ifn
  (:require
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :as grp.ana.disp]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.literals :as lit]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]))

(defn analyze:ifn-call!
  [env {:as td ifn ::grp.art/original-expression} args-td]
  {::grp.art/samples (set (map (partial apply ifn)
                            (grp.sampler/random-samples-from-each env args-td)))})

(defmethod grp.ana.disp/analyze-mm :ifn/call [env [ifn & args :as sexpr]]
  (let [ifn-td (grp.ana.disp/-analyze! env ifn)
        args-td (map (partial grp.ana.disp/-analyze! env) args)]
    (if-let [ifn-kind (::lit/kind ifn-td)]
      (case ifn-kind
        (::lit/quoted-symbol ::lit/keyword ::lit/map ::lit/set)
        (analyze:ifn-call! env ifn-td args-td)
        #_:else
        (grp.ana.disp/unknown-expr env sexpr))
      (analyze:ifn-call! env ifn-td args-td))))

(defmethod grp.ana.disp/analyze-mm :ifn/literal [env sexpr]
  {::grp.art/original-expression sexpr
   ::grp.art/samples #{sexpr}})
