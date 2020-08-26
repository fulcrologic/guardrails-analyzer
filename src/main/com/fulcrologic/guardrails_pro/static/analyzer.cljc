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
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.utils :as grp.u]
    [taoensso.timbre :as log])
  (:import
    (java.util.regex Pattern)))

(defn regex? [x]
  (= (type x) Pattern))

(defn list-dispatch-type [env [f :as _sexpr]]
  (cond
    (grp.art/function-detail env f) :function-call
    (symbol? f) f
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
    (grp.art/update-location (grp.u/?meta sexpr))
    (analyze-mm sexpr)))

(defmethod analyze-mm :default [_ sexpr]
  (log/warn "Could not analyze:" (pr-str sexpr))
  {})

(defmethod analyze-mm :literal [_ sexpr]
  (let [spec (cond
               (char? sexpr) char?
               (number? sexpr) number?
               (string? sexpr) string?
               (keyword? sexpr) keyword?
               (regex? sexpr) regex?
               (nil? sexpr) nil?)]
    {::grp.art/spec                spec
     ::grp.art/samples             #{sexpr}
     ::grp.art/original-expression sexpr}))

(defmethod analyze-mm :symbol [env sym]
  (or (grp.art/symbol-detail env sym) {}))

(defmethod analyze-mm :function-call [env [function & arguments]]
  (grp.fnt/calculate-function-type env function
    (mapv (partial analyze! env) arguments)))

(defn analyze-statements! [env body]
  (doseq [expr (butlast body)]
    (analyze! env expr))
  (analyze! env (last body)))

(defmethod analyze-mm 'do [env [_ & body]]
  (analyze-statements! env body))

(defn analyze-let-like-form [env [_ bindings & body]]
  (analyze-statements!
    (reduce (fn [env [bind-sexpr sexpr]]
              ;; TASK: update location & test
              (reduce-kv grp.art/remember-local
                env (grp.u/destructure* env bind-sexpr
                      (analyze! env sexpr))))
      env (partition 2 bindings))
    body))

(defmethod analyze-mm 'let [env sexpr] (analyze-let-like-form env sexpr))
(defmethod analyze-mm 'clojure.core/let [env sexpr] (analyze-let-like-form env sexpr))
(defmethod analyze-mm 'cljs.core/let [env sexpr] (analyze-let-like-form env sexpr))

(comment
  (com.fulcrologic.guardrails-pro.core/>defn foo [x]
    [int? => int?]
    (inc x))

  (grp.art/function-detail (grp.art/build-env) `foo)
  )
