;; Copyright (c) Fulcrologic, LLC. All rights reserved.
;;
;; Permission to use this software requires that you
;; agree to our End-user License Agreement, legally obtain a license,
;; and use this software within the constraints of the terms specified
;; by said license.
;;
;; You may NOT publish, redistribute, or reproduce this software or its source
;; code in any form (printed, electronic, or otherwise) except as explicitly
;; allowed by your license agreement..

(ns com.fulcrologic.copilot.analysis.analyzer.ifn
  (:require
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as cp.ana.disp]
    [com.fulcrologic.copilot.analysis.analyzer.literals :as lit]
    [com.fulcrologic.copilot.analysis.sampler :as cp.sampler]
    [com.fulcrologic.copilot.artifacts :as cp.art]))

(defn analyze:ifn-call!
  [env td args-td]
  {::cp.art/samples (set (map (fn [[f & args]] (apply f args))
                           (cp.sampler/random-samples-from-each env
                             (cons td args-td))))})

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
