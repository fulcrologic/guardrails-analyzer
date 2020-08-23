(ns com.fulcrologic.guardrails-pro.static.analyzer
  "An extensible analyzer for expressions (including special forms).

   * Call `analyze` on the expression.
   * If it is a call to a function in the registry, then we dispatch to a multimethod that can
     do proper steps for type extraction (which may call analyze on arguments to get type
     descriptors).
   ** Analyze may find it to be trivially a type (e.g. primitive/literal)
   ** The analyzer can detect special forms (i.e. `let`) and process them, which in turn
      will need to recursively ask for the `type-description` of each binding value.
      NOTE: There can be any number of errors detected during this analysis.
   ** The end result is a `type-description` (which can include errors)
   "
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.static.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.utils :as grp.u]
    [taoensso.timbre :as log])
  (:import
    (java.util.regex Pattern)))

(defn regex? [x]
  (= (type x) Pattern))

(defn list-dispatch-type [env [f :as sexpr]]
  (cond
    (a/function-detail env f) :function-call
    (symbol? f) f
    :else :unknown))

(defmulti analyze-mm
  (fn [env sexpr]
    (let [dispatch (cond
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

                     :else :unknown)]
      (log/spy :info dispatch))))

(>defn analyze!
  [env sexpr]
  [::a/env any? => ::a/type-description]
  (analyze-mm env sexpr))

(defmethod analyze-mm :default [env sexpr]
  (log/warn "Could not analyze:" sexpr)
  {})

(defmethod analyze-mm :literal [env sexpr]
  (log/info "Analyze literal " sexpr)
  (let [spec (cond
               (char? sexpr) char?
               (number? sexpr) number?
               (string? sexpr) string?
               (keyword? sexpr) keyword?
               (regex? sexpr) regex?
               (nil? sexpr) nil?)]
    {::a/spec                spec
     ::a/samples             #{sexpr}
     ::a/original-expression sexpr}))

(defmethod analyze-mm :symbol [env sym]
  (log/spy :info ["analyze symbol" sym])
  (log/spy :info (or (a/symbol-detail env sym) {})))

(defmethod analyze-mm :function-call [env [function & arguments :as call]]
  (let [{::a/keys [arities]} (a/function-detail env function)
        {::a/keys [gspec]} (get arities (count arguments) (get arities :n))
        {::a/keys [arg-specs]} gspec
        arg-value-descriptions (mapv (partial analyze! env) arguments)]
    (doseq [[raw-argument
             argument-spec
             {::a/keys [samples] :as real-argument-type-descriptor}] (map vector arguments arg-specs arg-value-descriptions)]
      (log/info "Analyzing argument:" [raw-argument argument-spec real-argument-type-descriptor])
      ;; TASK: Careful...type descr can be a gspec for HOF
      (let [checkable?   (and argument-spec (seq samples))
            failing-case (and checkable? (some (fn [sample] (when-not (s/valid? argument-spec sample) sample)) samples))]
        ;; TASK: send location estimate in env???
        ;; TASK: when (not checkable?) (a/record-weakness! env ) so we can highlight where we cannot check
        (when failing-case
          (a/record-problem! env {::a/original-expression raw-argument
                                  ::a/expected            argument-spec
                                  ;; TASK: decide how to report other details
                                  ::a/actual              failing-case
                                  ::a/message             (str "Argument " (pr-str raw-argument) " in the call  " call
                                                            " could have the value "
                                                            failing-case
                                                            ", which fails for the declared type of that argument.")}))))
    (grp.fnt/calculate-function-type env function arg-value-descriptions)))

(defn analyze-statements! [env body]
  (doseq [expr (butlast body)]
    (analyze! env expr))
  (analyze! env (last body)))

(defmethod analyze-mm 'do [env [_ & body]]
  (log/info "Analyzing a sequence of statements")
  (analyze-statements! env body))

(defn analyze-let-like-form [env [_ bindings & body]]
  (log/info "Analyzing a let")
  (analyze!
    (reduce (fn [env [bind-sym sexpr]]
              ;; TASK: Handle destructuring
              (assoc-in env [::a/local-symbols bind-sym]
                (analyze! env sexpr)))
      env (partition 2 bindings))
    `(do ~@body)))

(defmethod analyze-mm 'let [env sexpr] (analyze-let-like-form env sexpr))
(defmethod analyze-mm 'clojure.core/let [env sexpr] (analyze-let-like-form env sexpr))
(defmethod analyze-mm 'cljs.core/let [env sexpr] (analyze-let-like-form env sexpr))
(defmethod analyze-mm 'taoensso.encore/when-let [env sexpr] (analyze-let-like-form env sexpr))
