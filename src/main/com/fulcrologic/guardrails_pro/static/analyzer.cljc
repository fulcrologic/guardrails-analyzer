(ns com.fulcrologic.guardrails-pro.static.analyzer
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.static.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.utils :as grp.u]
    [taoensso.timbre :as log]
    [taoensso.encore :as enc])
  (:import
    #?@(:clj [(java.util.regex Pattern)])))

(defn regex? [x]
  #?(:clj (= (type x) Pattern)
     :cljs (regexp? x)))

(defn list-dispatch-type [env [f :as _sexpr]]
  (cond
    (grp.art/function-detail env f) :function-call
    (grp.art/external-function-detail env f) :external-function
    (symbol? f)  (grp.art/cljc-rewrite-sym-ns f)
    ;; TODO: ifn? eg: :kw 'sym {} ...
    :else :unknown))

(defmulti analyze-mm
  (fn [env sexpr]
    (log/spy :info :dispatch
      (cond
        (seq? sexpr) (list-dispatch-type env sexpr)
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

        :else :unknown))))

(>defn analyze!
  [env sexpr]
  [::grp.art/env any? => ::grp.art/type-description]
  (log/info "analyzing:" (pr-str sexpr))
  (-> env
    (grp.art/update-location (meta sexpr))
    (analyze-mm sexpr)))

(defmethod analyze-mm :default [_ sexpr]
  (log/warn "Could not analyze:" (pr-str sexpr))
  {})

(defmethod analyze-mm :literal [_ sexpr]
  (log/spy :debug :analyze/literal
    (let [spec (cond
                 (char? sexpr) char?
                 (number? sexpr) number?
                 (string? sexpr) string?
                 (keyword? sexpr) keyword?
                 (regex? sexpr) regex?
                 (nil? sexpr) nil?)]
      {::grp.art/spec                spec
       ::grp.art/samples             [sexpr]
       ::grp.art/original-expression sexpr})))

(defmethod analyze-mm :symbol [env sym]
  (or (grp.art/symbol-detail env sym) {}))

(>defn validate-samples! [env k v samples]
  [::grp.art/env any? any? ::grp.art/samples => (? ::grp.art/samples)]
  (enc/if-let [spec (s/get-spec k)
               failing-sample (some (fn _invalid-sample [sample]
                                      (when-not (s/valid? spec sample) sample))
                                samples)]
    (do (grp.art/record-error! env
          {::grp.art/original-expression v
           ::grp.art/expected {::grp.art/spec spec}
           ::grp.art/actual {::grp.art/failing-samples [failing-sample]}
           ::grp.art/message (str "Value in map: " failing-sample " failed to pass spec for " k ".")})
      nil)
    samples))

(>defn reduce-hashmap-samples [env acc k v]
  [::grp.art/env map? any? any? => (? ::grp.art/samples)]
  (assoc acc k
    (let [{::grp.art/keys [samples]} (analyze! env v)]
      (if (seq samples)
        (validate-samples! env k v samples)
        (do (grp.art/record-warning! env
              {::grp.art/original-expression v
               ::grp.art/message (str "Failed to generate values for: " v)})
          nil)))))

(>defn analyze-hashmap! [env hashmap]
  [::grp.art/env map? => ::grp.art/type-description]
  (let [sample-map (reduce-kv (partial reduce-hashmap-samples env) {} hashmap)]
    (if (some nil? (vals sample-map))
      (do (grp.art/record-warning! env
            {::grp.art/original-expression hashmap
             ::grp.art/message (str "Failed to generate samples for literal map due to earlier spec failure.")})
        {})
      {::grp.art/samples (grp.sampler/try-sampling! env
                           (grp.sampler/hashmap-permutation-generator sample-map)
                           {::grp.art/original-expression hashmap})
       ::grp.art/type    "literal-hashmap"})))

(defmethod analyze-mm :collection [env coll]
  (cond
    (map? coll) (analyze-hashmap! env coll)
    ;; TODO
    :else       {}))

(defmethod analyze-mm :external-function [env [f & args]]
  (let [fd (grp.art/external-function-detail env f)
        argtypes (mapv (partial analyze! env) args)]
    {::grp.art/samples (grp.sampler/sample! env fd argtypes)}))

(defmethod analyze-mm :function-call [env [function & arguments]]
  (grp.fnt/calculate-function-type env function
    (mapv (partial analyze! env) arguments)))

(defn analyze-statements! [env body]
  (doseq [expr (butlast body)]
    (analyze! env expr))
  (analyze! env (last body)))

(defmethod analyze-mm 'do [env [_ & body]]
  (analyze-statements! env body))

(defn analyze-let-like-form! [env [_ bindings & body]]
  (analyze-statements!
    (reduce (fn [env [bind-sexpr sexpr]]
              ;; TODO: update location & test
              (reduce-kv grp.art/remember-local
                env (grp.u/destructure* env bind-sexpr
                      (analyze! env sexpr))))
      env (partition 2 bindings))
    body))

(defmethod analyze-mm 'let [env sexpr] (analyze-let-like-form! env sexpr))
(defmethod analyze-mm 'clojure.core/let [env sexpr] (analyze-let-like-form! env sexpr))

(defmethod analyze-mm '-> [env [_ subject & args]]
  (analyze! env
    (reduce (fn [acc form]
              (with-meta
                (if (seq? form)
                  (apply list (first form) acc (rest form))
                  (list form acc))
                (meta form)))
      subject args)))

;; HOF notes
;(>defn f [m]
;  (let [a (range 1 2)                                       ;; (s/coll-of int?)
;        (>fn [a] [int? => string?]) (comp
;                                      (>fn [a] [map? => string?])
;                                      (>fn [a] [(>fspec [n] [int? => int?]) => map?])
;                                      some-f
;                                      #_(>fn [a] [int? => (>fspec [x] [number? => number?] string?)]))
;        bb (into #{}
;             (comp
;               (map f) ;; >fspec ...
;               (filter :person/fat?))
;             people)
;        new-seq (map (>fn [x] ^:boo [int? => int?]
;                       (map (fn ...) ...)
;                       m) a)]))

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
  throw
  when
  when-let
  when-not
  )
