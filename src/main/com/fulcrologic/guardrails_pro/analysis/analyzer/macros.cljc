(ns com.fulcrologic.guardrails-pro.analysis.analyzer.macros
  (:require
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :as grp.ana.disp]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.literals]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]))

(defn analyze-single-arity! [env [arglist gspec & body]]
  (let [gspec  (grp.fnt/interpret-gspec env arglist gspec)
        env    (grp.fnt/bind-argument-types env arglist gspec)
        result (grp.ana.disp/analyze-statements! env body)]
    (grp.fnt/check-return-type! env gspec result (last body))))

(defn analyze:>defn! [env [_ defn-sym & defn-forms :as sexpr]]
  (let [env (assoc env ::grp.art/checking-sym defn-sym)
        arities (drop-while (some-fn string? map?) defn-forms)]
    (if (vector? (first arities))
      (analyze-single-arity! env arities)
      (doseq [arity arities]
        (analyze-single-arity! env arity))))
  {})

(defmethod grp.ana.disp/analyze-mm '>defn    [env sexpr] (analyze:>defn! env sexpr))
(defmethod grp.ana.disp/analyze-mm `gr/>defn [env sexpr] (analyze:>defn! env sexpr))

(defmethod grp.ana.disp/analyze-mm 'do [env [_ & body]] (grp.ana.disp/analyze-statements! env body))

(defn analyze-let-like-form! [env [_ bindings & body]]
  (grp.ana.disp/analyze-statements!
    (reduce (fn [env [bind-sexpr sexpr]]
              ;; TODO: update location & test
              (reduce-kv grp.art/remember-local
                env (grp.fnt/destructure! env bind-sexpr
                      (grp.ana.disp/-analyze! env sexpr))))
      env (partition 2 bindings))
    body))

(defmethod grp.ana.disp/analyze-mm 'let [env sexpr] (analyze-let-like-form! env sexpr))
(defmethod grp.ana.disp/analyze-mm 'clojure.core/let [env sexpr] (analyze-let-like-form! env sexpr))

;; TODO macros
(comment
  and
  case
  cond
  condp
  doseq
  for
  if
  if-let
  if-not
  letfn
  loop
  recur
  or
  try
  catch
  finally
  throw
  when
  when-let
  when-not
  )

;; TODO thread macros
(comment
  ->>
  some->
  some->>
  cond->
  cond->>
  as->
  )

(defmethod grp.ana.disp/analyze-mm '-> [env [_ subject & args]]
  (grp.ana.disp/-analyze! env
    (reduce (fn [acc form]
              (with-meta
                (if (seq? form)
                  (apply list (first form) acc (rest form))
                  (list form acc))
                (meta form)))
      subject args)))
