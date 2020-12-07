(ns com.fulcrologic.copilot.analysis.analyzer.macros
  (:require
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as cp.ana.disp]
    [com.fulcrologic.copilot.analysis.analyzer.literals]
    [com.fulcrologic.copilot.analysis.destructuring :as cp.destr]
    [com.fulcrologic.copilot.analysis.function-type :as cp.fnt]
    [com.fulcrologic.copilot.analysis.sampler :as cp.sampler]
    [com.fulcrologic.copilot.analysis.spec :as cp.spec]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.guardrails.core :as gr]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defmethod cp.ana.disp/analyze-mm 'comment [env sexpr] {})

(defn analyze-single-arity! [env defn-sym [arglist gspec & body]]
  (let [gspec  (cp.fnt/interpret-gspec env arglist gspec)
        env    (cp.fnt/bind-argument-types env arglist gspec)
        result (cp.ana.disp/analyze-statements! env body)]
    (cp.fnt/check-return-type! env gspec result (meta defn-sym))))

(defn analyze:>defn! [env [_ defn-sym & defn-forms :as sexpr]]
  (let [env (assoc env ::cp.art/checking-sym defn-sym)
        arities (drop-while (some-fn string? map?) defn-forms)]
    (if (vector? (first arities))
      (analyze-single-arity! env defn-sym arities)
      (doseq [arity arities]
        (analyze-single-arity! env defn-sym arity))))
  {})

(defmethod cp.ana.disp/analyze-mm '>defn     [env sexpr] (analyze:>defn! env sexpr))
(defmethod cp.ana.disp/analyze-mm `gr/>defn  [env sexpr] (analyze:>defn! env sexpr))
(defmethod cp.ana.disp/analyze-mm '>defn-    [env sexpr] (analyze:>defn! env sexpr))
(defmethod cp.ana.disp/analyze-mm `gr/>defn- [env sexpr] (analyze:>defn! env sexpr))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/do [env [_ & body]]
  (cp.ana.disp/analyze-statements! env body))

(defn analyze-let-bindings! [env bindings]
  (reduce (fn [env [bind-sexpr sexpr]]
            (reduce-kv cp.art/remember-local
              env (cp.destr/destructure! env bind-sexpr
                    (cp.ana.disp/-analyze! env sexpr))))
    env (partition 2 bindings)))

(defn analyze-let-like-form! [env [_ bindings & body]]
  (cp.ana.disp/analyze-statements!
    (analyze-let-bindings! env bindings)
    body))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/let [env sexpr]
  (analyze-let-like-form! env sexpr))

(defn analyze:>letfn [env [_ fns & body]]
  ;;TODO:
  ;; two pass analysis
  ;; 1. bind fn gspec's
  ;; 2. analyze fn bodies & letfn body
  )

(defmethod cp.ana.disp/analyze-mm 'clojure.core/if [env [_ condition then & [else]]]
  ;; TODO: on each branch (then & else) update env locals used in condition:
  ;; if predicate has sampler, look it up & call it to filter locals used
  ;; otherwise:
  ;; - ? maybe further analysis cannot be done
  ;; - ? maybe annotate x as not accurate
  #_(if (even? x)
      (str "EVEN:" x) ;; x should     be even?
      (str "ODD:" x)) ;; x should not be even?
  (let [C (cp.ana.disp/-analyze! env condition)
        T (cp.ana.disp/-analyze! env then)
        E (if else
            (cp.ana.disp/-analyze! env else)
            {::cp.art/samples (set (cp.spec/sample env (cp.spec/generator env nil?)))})]
    (log/debug "IF:" C "THEN:" T)
    (when (not (some identity (::cp.art/samples C)))
      (cp.art/record-warning! env condition
        :warning/if-condition-never-reaches-then-branch))
    (when (and E (not (some not (::cp.art/samples C))))
      (cp.art/record-warning! env condition
        :warning/if-condition-never-reaches-else-branch))
    {::cp.art/samples (cp.sampler/random-samples-from env T E)}))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/if-let [env [_ [bind-sym bind-expr] then & [else]]]
  (cp.ana.disp/-analyze! env
    `(let [t# ~bind-expr]
       (if t#
         (let [~bind-sym t#] ~then)
         ~else))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/if-not [env [_ condition then & [else]]]
  (cp.ana.disp/-analyze! env
    `(if (not ~condition) ~then ~else)))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/when [env [_ condition & body]]
  (cp.ana.disp/-analyze! env
    `(if ~condition (do ~@body))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/when-let [env [_ bindings & body]]
  (cp.ana.disp/-analyze! env
    `(if-let ~bindings (do ~@body))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/when-not [env [_ condition & body]]
  (cp.ana.disp/-analyze! env
    `(if (not ~condition) (do ~@body))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/and [env [_ & exprs]]
  (if (empty? exprs)
    {::cp.art/samples #{true}}
    (letfn [(AND [exprs]
              (when-let [[expr & rst] (seq exprs)]
                `(let [t# ~expr]
                   (if t# ~(AND rst) t#))))]
      (cp.ana.disp/-analyze! env (AND exprs)))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/or [env [_ & exprs]]
  (if (empty? exprs)
    {::cp.art/samples #{nil}}
    (letfn [(OR [exprs]
              (when-let [[expr & rst] (seq exprs)]
                `(let [t# ~expr]
                   (if t# t# ~(OR rst)))))]
      (cp.ana.disp/-analyze! env (OR exprs)))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/cond [env [_ & clauses]]
  (if (empty? clauses)
    {::cp.art/samples #{nil}}
    (letfn [(COND [clauses]
              (when-let [[tst expr & rst] (seq clauses)]
                `(if ~tst ~expr ~(COND rst))))]
      (cp.ana.disp/-analyze! env (COND clauses)))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/-> [env [_ subject & args]]
  (cp.ana.disp/-analyze! env
    (reduce (fn [subject step]
              (with-meta
                (if (seq? step)
                  (apply list (first step) subject (rest step))
                  (list step subject))
                (meta step)))
      subject args)))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/->> [env [_ subject & args]]
  (cp.ana.disp/-analyze! env
    (reduce (fn [subject step]
              (with-meta
                (if (seq? step)
                  (seq (conj (vec step) subject))
                  (list step subject))
                (meta step)))
      subject args)))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/as-> [env [_ expr subject & args]]
  (analyze-let-like-form! env
    ['_ (reduce (fn [bindings step]
                  (conj bindings
                    subject step))
          [subject expr] args)
     subject]))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/some-> [env [_ subject & args]]
  (analyze-let-like-form! env
    ['_ (reduce (fn [bindings step]
                  (conj bindings
                    subject `(if (nil? ~subject)
                               nil (~'-> ~subject ~step))))
          [] args)
     subject]))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/some->> [env [_ subject & args]]
  (analyze-let-like-form! env
    ['_ (reduce (fn [bindings step]
                  (conj bindings
                    subject `(if (nil? ~subject)
                               nil (~'->> ~subject ~step))))
          [] args)
     subject]))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/cond-> [env [_ subject & args]]
  (analyze-let-like-form! env
    ['_ (reduce (fn [bindings [tst step]]
                  (conj bindings
                    subject `(if ~tst
                               (~'-> ~subject ~step)
                               ~subject)))
          [] (partition 2 args))
     subject]))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/cond->> [env [_ subject & args]]
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
            (let [bind-sexpr (cp.art/unwrap-meta bind-sexpr)]
              (case bind-sexpr
                :let (analyze-let-bindings! env sexpr)
                (:when :while) env
                (reduce-kv cp.art/remember-local
                  env (cp.destr/destructure! env bind-sexpr
                        (let [td (cp.ana.disp/-analyze! env sexpr)]
                          (if-not (every? seqable? (::cp.art/samples td))
                            (do (cp.art/record-error! env sexpr
                                  :error/expected-seqable-collection)
                              {})
                            (update td ::cp.art/samples
                              (comp set (partial mapcat identity))))))))))
    env (partition 2 bindings)))

(defn analyze-for-loop! [env bindings body]
  (-> env
    (analyze-for-bindings! bindings)
    (cp.ana.disp/analyze-statements! body)
    (update ::cp.art/samples (comp hash-set vec))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/for [env [_ bindings & body]]
  (analyze-for-loop! env bindings body))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/doseq [env [_ bindings & body]]
  (analyze-for-loop! env bindings body)
  {::cp.art/samples #{nil}})
