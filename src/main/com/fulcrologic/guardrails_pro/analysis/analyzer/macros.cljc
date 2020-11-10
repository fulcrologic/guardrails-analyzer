(ns com.fulcrologic.guardrails-pro.analysis.analyzer.macros
  (:require
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :as grp.ana.disp]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.literals]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails.core :as gr]
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

(defmethod grp.ana.disp/analyze-mm '>defn     [env sexpr] (analyze:>defn! env sexpr))
(defmethod grp.ana.disp/analyze-mm `gr/>defn  [env sexpr] (analyze:>defn! env sexpr))
(defmethod grp.ana.disp/analyze-mm '>defn-    [env sexpr] (analyze:>defn! env sexpr))
(defmethod grp.ana.disp/analyze-mm `gr/>defn- [env sexpr] (analyze:>defn! env sexpr))

(defmethod grp.ana.disp/analyze-mm 'do [env [_ & body]] (grp.ana.disp/analyze-statements! env body))

(defn analyze-let-bindings! [env bindings]
  (reduce (fn [env [bind-sexpr sexpr]]
            (reduce-kv grp.art/remember-local
              env (grp.fnt/destructure! env bind-sexpr
                    (grp.ana.disp/-analyze! env sexpr))))
    env (partition 2 bindings)))

(defn analyze-let-like-form! [env [_ bindings & body]]
  (grp.ana.disp/analyze-statements!
    (analyze-let-bindings! env bindings)
    body))

(defmethod grp.ana.disp/analyze-mm 'let [env sexpr] (analyze-let-like-form! env sexpr))
(defmethod grp.ana.disp/analyze-mm 'clojure.core/let [env sexpr] (analyze-let-like-form! env sexpr))

(defn analyze:>letfn [env [_ fns & body]]
  ;;TODO:
  ;; two pass analysis
  ;; 1. bind fn gspec's
  ;; 2. analyze fn bodies & letfn body
  )

(defmethod grp.ana.disp/analyze-mm 'if [env [_ condition then & [else]]]
  ;; TODO: on each branch (then & else) update env locals used in condition:
  ;; if predicate has sampler, look it up & call it to filter locals used
  ;; otherwise:
  ;; - ? maybe further analysis cannot be done
  ;; - ? maybe annotate x as not accurate
  #_(if (even? x)
      (str "EVEN:" x) ;; x should     be even?
      (str "ODD:" x)) ;; x should not be even?
  (let [C (grp.ana.disp/-analyze! env condition)
        T (grp.ana.disp/-analyze! env then)
        E (if else
            (grp.ana.disp/-analyze! env else)
            {::grp.art/samples (set (grp.spec/sample env (grp.spec/generator env nil?)))})]
    (log/debug "IF:" C "THEN:" T)
    (when (not (some identity (::grp.art/samples C)))
      (grp.art/record-warning! env condition
        :warning/if-condition-never-reaches-then-branch))
    (when (and E (not (some not (::grp.art/samples C))))
      (grp.art/record-warning! env condition
        :warning/if-condition-never-reaches-else-branch))
    {::grp.art/samples (grp.sampler/random-samples-from env T E)}))

(defmethod grp.ana.disp/analyze-mm 'if-let [env [_ [bind-sym bind-expr] then & [else]]]
  (grp.ana.disp/-analyze! env
    `(let [t# ~bind-expr]
       (if t#
         (let [~bind-sym t#] ~then)
         ~else))))

(defmethod grp.ana.disp/analyze-mm 'if-not [env [_ condition then & [else]]]
  (grp.ana.disp/-analyze! env
    `(if (not ~condition) ~then ~else)))

(defmethod grp.ana.disp/analyze-mm 'when [env [_ condition & body]]
  (grp.ana.disp/-analyze! env
    `(if ~condition (do ~@body))))

(defmethod grp.ana.disp/analyze-mm 'when-let [env [_ bindings & body]]
  (grp.ana.disp/-analyze! env
    `(if-let ~bindings (do ~@body))))

(defmethod grp.ana.disp/analyze-mm 'when-not [env [_ condition & body]]
  (grp.ana.disp/-analyze! env
    `(if (not ~condition) (do ~@body))))

(defmethod grp.ana.disp/analyze-mm 'and [env [_ & exprs]]
  (if (empty? exprs)
    {::grp.art/samples #{true}}
    (letfn [(AND [exprs]
              (when-let [[expr & rst] (seq exprs)]
                `(let [t# ~expr]
                   (if t# ~(AND rst) t#))))]
      (grp.ana.disp/-analyze! env (AND exprs)))))

(defmethod grp.ana.disp/analyze-mm 'or [env [_ & exprs]]
  (if (empty? exprs)
    {::grp.art/samples #{nil}}
    (letfn [(OR [exprs]
              (when-let [[expr & rst] (seq exprs)]
                `(let [t# ~expr]
                   (if t# t# ~(OR rst)))))]
      (grp.ana.disp/-analyze! env (OR exprs)))))

(defmethod grp.ana.disp/analyze-mm 'cond [env [_ & clauses]]
  (if (empty? clauses)
    {::grp.art/samples #{nil}}
    (letfn [(COND [clauses]
              (when-let [[tst expr & rst] (seq clauses)]
                `(if ~tst ~expr ~(COND rst))))]
      (grp.ana.disp/-analyze! env (COND clauses)))))

;; TODO: difficult
(comment
  case
  condp)

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

(defn analyze-for-bindings! [env bindings]
  (reduce (fn [env [bind-sexpr sexpr]]
            (case bind-sexpr
              :let (analyze-let-bindings! env sexpr)
              (:when :while) env
              (reduce-kv grp.art/remember-local
                env (grp.fnt/destructure! env bind-sexpr
                      (let [td (grp.ana.disp/-analyze! env sexpr)]
                        (if-not (every? seqable? (::grp.art/samples td))
                          (do (grp.art/record-error! env sexpr
                                :error/expected-seqable-collection)
                            {})
                          (update td ::grp.art/samples
                            (comp set (partial mapcat identity)))))))))
    env (partition 2 bindings)))

(defn analyze-for-loop! [env bindings body]
  (-> env
    (analyze-for-bindings! bindings)
    (grp.ana.disp/analyze-statements! body)
    (update ::grp.art/samples (comp hash-set vec))))

(defmethod grp.ana.disp/analyze-mm 'for [env [_ bindings & body]]
  (analyze-for-loop! env bindings body))
(defmethod grp.ana.disp/analyze-mm 'clojure.core/for [env [_ bindings & body]]
  (analyze-for-loop! env bindings body))

(defmethod grp.ana.disp/analyze-mm 'doseq [env [_ bindings & body]]
  (analyze-for-loop! env bindings body)
  {::grp.art/samples #{nil}})
(defmethod grp.ana.disp/analyze-mm 'clojure.core/doseq [env [_ bindings & body]]
  (analyze-for-loop! env bindings body)
  {::grp.art/samples #{nil}})

;; TODO
(comment loop recur)

;; TODO
(comment try catch finally throw)
