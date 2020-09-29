(ns com.fulcrologic.guardrails-pro.analysis.analyzer
  (:require
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :refer [analyze-mm -analyze!]]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.literals]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn =>]]
    [taoensso.timbre :as log]))

(defn analyze!
  [env sexpr]
  [::grp.art/env any? => ::grp.art/type-description]
  (-analyze! env sexpr))

(defmethod analyze-mm :unknown [_ sexpr]
  (log/warn "Could not analyze:" (pr-str sexpr))
  {})

(defn analyze-statements! [env body]
  (doseq [expr (butlast body)]
    (-analyze! env expr))
  (-analyze! env (last body)))

(defn analyze-single-arity! [env [arglist gspec & body]]
  (log/debug "analyze-single-arity!" arglist gspec body)
  (let [gspec  (grp.fnt/interpret-gspec env arglist gspec)
        env    (grp.fnt/bind-argument-types env arglist gspec)
        result (analyze-statements! env body)]
    (grp.fnt/check-return-type! env gspec result (last body))))

(defn analyze:>defn! [env [_ defn-sym & defn-forms :as sexpr]]
  (let [env (assoc env ::grp.art/checking-sym defn-sym)
        arities (drop-while (some-fn string? map?) defn-forms)]
    (if (vector? (first arities))
      (analyze-single-arity! env arities)
      (doseq [arity arities]
        (analyze-single-arity! env arity))))
  {})

(defmethod analyze-mm '>defn    [env sexpr] (analyze:>defn! env sexpr))
(defmethod analyze-mm `gr/>defn [env sexpr] (analyze:>defn! env sexpr))

(defmethod analyze-mm :symbol.local/lookup [env sym]
  (or (grp.art/symbol-detail env sym) {}))

(defmethod analyze-mm :function.external/call [env [f & args]]
  (let [fd       (grp.art/external-function-detail env f)
        argtypes (mapv (partial -analyze! env) args)]
    {::grp.art/samples (grp.sampler/sample! env fd argtypes)}))

(defmethod analyze-mm :function/call [env [function & arguments]]
  (let [current-ns (some-> env ::grp.art/checking-sym namespace)
        fqsym      (if (simple-symbol? function) (symbol current-ns (name function)) function)]
    (log/spy :info [function fqsym])
    (grp.fnt/calculate-function-type env fqsym
      (mapv (partial -analyze! env) arguments))))

(defmethod analyze-mm :function.expression/call [env [f-expr & args]]
  (let [function (-analyze! env f-expr)
        argtypes (mapv (partial -analyze! env) args)]
    {::grp.art/samples (grp.sampler/sample! env function argtypes)}))

(defmethod analyze-mm 'do [env [_ & body]] (analyze-statements! env body))

(defn analyze-let-like-form! [env [_ bindings & body]]
  (analyze-statements!
    (reduce (fn [env [bind-sexpr sexpr]]
              ;; TODO: update location & test
              (reduce-kv grp.art/remember-local
                env (grp.fnt/destructure! env bind-sexpr
                      (-analyze! env sexpr))))
      env (partition 2 bindings))
    body))

(defmethod analyze-mm 'let [env sexpr] (analyze-let-like-form! env sexpr))
(defmethod analyze-mm 'clojure.core/let [env sexpr] (analyze-let-like-form! env sexpr))

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

(defmethod analyze-mm '-> [env [_ subject & args]]
  (-analyze! env
    (reduce (fn [acc form]
              (with-meta
                (if (seq? form)
                  (apply list (first form) acc (rest form))
                  (list form acc))
                (meta form)))
      subject args)))

(defn analyze-lambda! [env [_ fn-name]]
  (log/error "TODO ANALYZE LAMBDA")
  #_(let [{::grp.art/keys [lambdas]} (grp.art/function-detail env (::grp.art/checking-sym env))
        lambda (get lambdas fn-name {})]
    (doseq [{::grp.art/keys [body] :as arity-detail} (vals (::grp.art/arities lambda))]
      (let [env    (grp.fnt/bind-argument-types env arity-detail)
            result (analyze-statements! env body)]
        (log/info "Locals for " fn-name ":" (::grp.art/local-symbols env))
        (grp.fnt/check-return-type! env arity-detail (log/spy :info result))))
    lambda))

(defmethod analyze-mm '>fn [env sexpr] (analyze-lambda! env sexpr))
(defmethod analyze-mm `gr/>fn [env sexpr] (analyze-lambda! env sexpr))

;; TODO HOFs fn -> val
(comment
  reduce
  filter
  apply
  update
  sort-by
  group-by
  split-with
  partition-by
  swap!
  repeatedly
  iterate
  )

(defn analyze-map-like! [env [map-like-sym f & colls]]
  (let [lambda (log/spy :debug :lambda (-analyze! env f))
        colls (map (partial -analyze! env) colls)]
    {::grp.art/samples (grp.sampler/sample! env
                         (grp.art/external-function-detail env map-like-sym)
                         (cons lambda colls))}))

(defmethod analyze-mm 'map [env sexpr] (analyze-map-like! env sexpr))
(defmethod analyze-mm 'clojure.core/map [env sexpr] (analyze-map-like! env sexpr))

;; TODO HOFs * -> fn
(comment
  comp
  complement
  constantly
  partial
  fnil
  juxt
  )

(defn analyze-constantly! [env [const value]]
  (let [value-td (-analyze! env value)]
    (merge
      (get-in (grp.art/external-function-detail env const)
        [::grp.art/arities 1 ::grp.art/gspec ::grp.art/return-spec])
      #::grp.art{:lambda-name (gensym "constantly$")
                 :env->fn (fn [env] (grp.sampler/random-sample-fn value-td))})))

(defmethod analyze-mm 'constantly [env sexpr] (analyze-constantly! env sexpr))
(defmethod analyze-mm 'clojure.core/constantly [env sexpr] (analyze-constantly! env sexpr))

;; TODO transducers
(comment
  into
  sequence
  transduce
  eduction
  map
  cat
  mapcat
  filter
  remove
  take
  take-while
  take-nth
  drop
  drop-while
  replace
  partition-by
  partition-all
  keep
  keep-indexed
  map-indexed
  distinct
  interpose
  dedupe
  random-sample
  )
