(ns com.fulcrologic.guardrails-pro.analysis.analyzer.macros
  (:require
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :as grp.ana.disp]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.literals]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [taoensso.timbre :as log]))

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

(comment
  letfn)

#_(if (even? x)
    (str "EVEN:" x)
    (str "ODD:" x))
(defmethod grp.ana.disp/analyze-mm 'if [env [_ condition then & [else]]]
  ;; TASK: on each branch (then & else) update locals used in condition: filter such-that condition
  (let [C (grp.ana.disp/-analyze! env condition)
        T (grp.ana.disp/-analyze! env then)
        E (if else
            (grp.ana.disp/-analyze! env else)
            {::grp.art/samples (grp.spec/sample env nil?)})]
    (log/debug "IF:" C "THEN:" T)
    (when (not (some identity (::grp.art/samples C)))
      (grp.art/record-warning! env condition
        :warning/if-condition-never-reaches-then-branch))
    (when (and E (not (some not (::grp.art/samples C))))
      (grp.art/record-warning! env condition
        :warning/if-condition-never-reaches-else-branch))
    {::grp.art/samples (grp.sampler/random-samples-from env T E)}))

(comment
  and
  case
  cond
  condp
  if-let
  if-not
  or
  when
  when-let
  when-not)

(defmethod grp.ana.disp/analyze-mm '-> [env [_ subject & args]]
  (grp.ana.disp/-analyze! env
    (reduce (fn [subject step]
              (with-meta
                (if (seq? step)
                  (apply list (first step) subject (rest step))
                  (list step subject))
                (meta step)))
      subject args)))

(defmethod grp.ana.disp/analyze-mm '->> [env [_ subject & args]]
  (grp.ana.disp/-analyze! env
    (reduce (fn [subject step]
              (with-meta
                (if (seq? step)
                  (seq (conj (vec step) subject))
                  (list step subject))
                (meta step)))
      subject args)))

(defmethod grp.ana.disp/analyze-mm 'as-> [env [_ expr subject & args]]
  (analyze-let-like-form! env
    ['_ (reduce (fn [bindings step]
                  (conj bindings
                    subject step))
          [subject expr] args)
     subject]))

(defmethod grp.ana.disp/analyze-mm 'some-> [env [_ subject & args]]
  (analyze-let-like-form! env
    ['_ (reduce (fn [bindings step]
                  (conj bindings
                    subject `(if (nil? ~subject)
                               nil (~'-> ~subject ~step))))
          [] args)
     subject]))

(defmethod grp.ana.disp/analyze-mm 'some->> [env [_ subject & args]]
  (analyze-let-like-form! env
    ['_ (reduce (fn [bindings step]
                  (conj bindings
                    subject `(if (nil? ~subject)
                               nil (~'->> ~subject ~step))))
          [] args)
     subject]))

(defmethod grp.ana.disp/analyze-mm 'cond-> [env [_ subject & args]]
  (analyze-let-like-form! env
    ['_ (reduce (fn [bindings [tst step]]
                  (conj bindings
                    subject `(if ~tst
                               (~'-> ~subject ~step)
                               ~subject)))
          [] (partition 2 args))
     subject]))

(defmethod grp.ana.disp/analyze-mm 'cond->> [env [_ subject & args]]
  (analyze-let-like-form! env
    ['_ (reduce (fn [bindings [tst step]]
                  (conj bindings
                    subject `(if ~tst
                               (~'->> ~subject ~step)
                               ~subject)))
          [] (partition 2 args))
     subject]))

(comment
  doseq
  for
  loop
  recur)

(comment
  try
  catch
  finally
  throw)
