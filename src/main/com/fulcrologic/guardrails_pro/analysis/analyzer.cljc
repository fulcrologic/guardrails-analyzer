(ns com.fulcrologic.guardrails-pro.analysis.analyzer
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.guardrails-pro.core :as grp]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [taoensso.timbre :as log]
    [taoensso.encore :as enc])
  (:import
    #?@(:clj [(java.util.regex Pattern)])))

(defn regex? [x]
  #?(:clj  (= (type x) Pattern)
     :cljs (regexp? x)))

(declare analyze-mm)

(defn list-dispatch [env [head :as _sexpr]]
  (letfn [(symbol-dispatch [sym]
            (cond
              (grp.art/function-detail env sym)
              #_=> :function-call
              (grp.art/symbol-detail env sym)
              #_=> :symbol
              (and (namespace sym) (get (methods analyze-mm) (grp.art/cljc-rewrite-sym-ns sym)))
              #_=> (grp.art/cljc-rewrite-sym-ns sym)
              (get (methods analyze-mm) sym)
              #_=> sym
              (grp.art/external-function-detail env sym)
              #_=> :external-function
              :else :ifn))]
    (cond
      (seq? head) :function-expr
      (symbol? head) (symbol-dispatch head)
      (ifn? head) :ifn
      :else :unknown)))

(defn analyze-dispatch [env sexpr]
  (cond
    (seq? sexpr) (list-dispatch env sexpr)
    (symbol? sexpr) :symbol

    (char? sexpr) :literal
    (number? sexpr) :literal
    (string? sexpr) :literal
    (keyword? sexpr) :literal
    (regex? sexpr) :literal
    (nil? sexpr) :literal

    (vector? sexpr) :collection
    (set? sexpr) :collection
    (map? sexpr) :collection

    :else :unknown))

(defmulti analyze-mm
  (fn [env sexpr]
    (log/spy :info :dispatch
      (analyze-dispatch env sexpr)))
  :default :unknown)

(>defn analyze!
  [env sexpr]
  [::grp.art/env any? => ::grp.art/type-description]
  (log/info "analyzing:" (pr-str sexpr))
  (log/spy :debug "analyze! returned:"
    (-> env
      (grp.art/update-location (meta sexpr))
      (analyze-mm sexpr))))

(defmethod analyze-mm :unknown [_ sexpr]
  (log/warn "Could not analyze:" (pr-str sexpr))
  {})

(defmethod analyze-mm :literal [env sexpr]
  (log/spy :debug :analyze/literal
    (let [spec (cond
                 (char? sexpr) char?
                 (number? sexpr) number?
                 (string? sexpr) string?
                 (keyword? sexpr) (let [s (when (qualified-keyword? sexpr) (grp.spec/lookup env sexpr))]
                                    (when (and (qualified-keyword? sexpr) (not s))
                                      (grp.art/record-warning! env sexpr
                                        :warning/qualified-keyword-missing-spec))
                                    keyword?)
                 (regex? sexpr) regex?
                 (nil? sexpr) nil?)]
      {::grp.art/spec                spec
       ::grp.art/samples             #{sexpr}
       ::grp.art/original-expression sexpr})))

(defmethod analyze-mm :symbol [env sym]
  (or (grp.art/symbol-detail env sym) {}))

(>defn validate-samples! [env k v samples]
  [::grp.art/env any? any? ::grp.art/samples => (? ::grp.art/samples)]
  (let [spec (grp.spec/lookup env k)]
    (enc/if-let [spec           spec
                 failing-sample (some (fn _invalid-sample [sample]
                                        (when-not (grp.spec/valid? env spec sample) sample))
                                  samples)]
      (do
        (grp.art/record-error! env
          {::grp.art/original-expression v
           ::grp.art/expected            {::grp.art/spec spec ::grp.art/type (pr-str k)}
           ::grp.art/actual              {::grp.art/failing-samples #{failing-sample}}
           ::grp.art/problem-type        :error/value-failed-spec})
        samples)
      (when-let [valid-samples (and spec (seq (filter (partial grp.spec/valid? env spec) samples)))]
        (set valid-samples)))))

(defn- analyze-hashmap-entry
  [env acc k v]
  (when (and (qualified-keyword? k) (nil? (grp.spec/lookup env k)))
    (grp.art/record-warning! env k :warning/qualified-keyword-missing-spec))
  (let [sample-value (let [{::grp.art/keys [samples]} (analyze! env v)]
                       (validate-samples! env k v samples)
                       (if (seq samples)
                         (rand-nth (vec samples))
                         (do
                           (grp.art/record-warning! env v :warning/missing-samples)
                           ::grp.art/Unknown)))]
    (assoc acc k sample-value)))

(>defn analyze-hashmap! [env hashmap]
  [::grp.art/env map? => ::grp.art/type-description]
  (let [sample-map (reduce-kv (partial analyze-hashmap-entry env) {} hashmap)]
    {::grp.art/samples             #{sample-map}
     ::grp.art/original-expression hashmap
     ::grp.art/type                "literal-hashmap"}))

(defn- analyze-vector-entry
  [env acc v]
  (let [sample (let [{::grp.art/keys [samples]} (analyze! env v)]
                 (when (seq samples) {:sample-value (rand-nth (vec samples))}))]
    (if (contains? sample :sample-value)
      (conj acc (:sample-value sample))
      (conj acc ::grp.art/Unknown))))

(>defn analyze-vector! [env v]
  [::grp.art/env vector? => ::grp.art/type-description]
  (let [sample-vector (reduce (partial analyze-vector-entry env) [] v)]
    {::grp.art/samples             #{sample-vector}
     ::grp.art/original-expression v
     ::grp.art/type                "literal-vector"}))

(defmethod analyze-mm :collection [env coll]
  (cond
    (map? coll) (analyze-hashmap! env coll)
    (vector? coll) (analyze-vector! env coll)
    ;; TODO
    :else {}))

(defmethod analyze-mm :external-function [env [f & args]]
  (let [fd       (grp.art/external-function-detail env f)
        argtypes (mapv (partial analyze! env) args)]
    {::grp.art/samples (grp.sampler/sample! env fd argtypes)}))

(defmethod analyze-mm :function-call [env [function & arguments]]
  (let [current-ns (some-> env ::grp.art/checking-sym namespace)
        fqsym      (if (simple-symbol? function) (symbol current-ns (name function)) function)]
    (log/spy :info [function fqsym])
    (grp.fnt/calculate-function-type env fqsym
      (mapv (partial analyze! env) arguments))))

(defmethod analyze-mm :function-expr [env [f-expr & args]]
  (let [function (analyze! env f-expr)
        argtypes (mapv (partial analyze! env) args)]
    {::grp.art/samples (grp.sampler/sample! env function argtypes)}))

(defn analyze-statements! [env body]
  (doseq [expr (butlast body)]
    (analyze! env expr))
  (analyze! env (last body)))

(defmethod analyze-mm 'do [env [_ & body]] (analyze-statements! env body))

(defn analyze-let-like-form! [env [_ bindings & body]]
  (analyze-statements!
    (reduce (fn [env [bind-sexpr sexpr]]
              ;; TODO: update location & test
              (reduce-kv grp.art/remember-local
                env (grp.fnt/destructure! env bind-sexpr
                      (analyze! env sexpr))))
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
  (analyze! env
    (reduce (fn [acc form]
              (with-meta
                (if (seq? form)
                  (apply list (first form) acc (rest form))
                  (list form acc))
                (meta form)))
      subject args)))

(defn analyze-lambda! [env [_ fn-name]]
  (let [{::grp.art/keys [lambdas]} (grp.art/function-detail env (::grp.art/checking-sym env))
        lambda (get lambdas fn-name {})]
    (doseq [{::grp.art/keys [body] :as arity-detail} (vals (::grp.art/arities lambda))]
      (let [env    (grp.fnt/bind-argument-types env arity-detail)
            result (analyze-statements! env body)]
        (log/info "Locals for " fn-name ":" (::grp.art/local-symbols env))
        (grp.fnt/check-return-type! env arity-detail (log/spy :info result))))
    lambda))

(defmethod analyze-mm '>fn [env sexpr] (analyze-lambda! env sexpr))
(defmethod analyze-mm `grp/>fn [env sexpr] (analyze-lambda! env sexpr))

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
  (let [lambda (log/spy :debug :lambda (analyze! env f))
        colls (map (partial analyze! env) colls)]
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

(defn analyze-partial! [env [_ f & args]]
  ; (fn [f & args] (fn [more] (apply f (concat args more))))
  (let [func (analyze! env f)
        args (map (partial analyze! env) args)]
    {}))

(defmethod analyze-mm 'partial [env sexpr] (analyze-partial! env sexpr))
(defmethod analyze-mm 'clojure.core/partial [env sexpr] (analyze-partial! env sexpr))

;; TODO: both top level >ftag and analyze-mm
(defn analyze-constantly! [env [const value]]
  (let [value-td (analyze! env value)]
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
