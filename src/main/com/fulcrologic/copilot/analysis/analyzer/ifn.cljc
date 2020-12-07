(ns com.fulcrologic.copilot.analysis.analyzer.ifn
  (:require
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as cp.ana.disp]
    [com.fulcrologic.copilot.analysis.analyzer.literals :as lit]
    [com.fulcrologic.copilot.analysis.sampler :as cp.sampler]
    [com.fulcrologic.copilot.artifacts :as cp.art]))

(defn analyze:ifn-call!
  [env {:as td ifn ::cp.art/original-expression} args-td]
  ;; NOTE: unsure about using original-expression... and unwrap-meta is a temporary fix
  {::cp.art/samples (set (map (partial apply (cp.art/unwrap-meta ifn))
                           (cp.sampler/random-samples-from-each env args-td)))})

(defmethod cp.ana.disp/analyze-mm :ifn/call [env [ifn & args :as sexpr]]
  (let [ifn-td (cp.ana.disp/-analyze! env ifn)
        args-td (map (partial cp.ana.disp/-analyze! env) args)]
    (if-let [ifn-kind (::lit/kind ifn-td)]
      (case ifn-kind
        (::lit/quoted-symbol ::lit/keyword ::lit/map ::lit/set)
        (analyze:ifn-call! env ifn-td args-td)
        #_:else
        (cp.ana.disp/unknown-expr env (::cp.art/original-expression ifn-td)))
      (analyze:ifn-call! env ifn-td args-td))))

(defmethod cp.ana.disp/analyze-mm :ifn/literal [env sexpr]
  {::cp.art/original-expression sexpr
   ::cp.art/samples #{sexpr}})
