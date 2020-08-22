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
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.static.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.utils :as grp.u]
    [taoensso.timbre :as log])
  (:import
    (java.util.regex Pattern)))

(defn regex? [x]
  (= (type x) Pattern))

(defn fn-call-dispatch-type [env function]
  (or (when (a/function-detail env function)
        :function-call)
    :unknown))

(defn list-dispatch-type [env sexpr]
  (or (when (symbol? (first sexpr))
        (case (str (first sexpr))
          "do"  :do
          ("let" "clojure.core/let") :let
          false))
    (fn-call-dispatch-type env (first sexpr))))

(defmulti analyze
  (fn [env sexpr]
    (cond
      (seq? sexpr)    (list-dispatch-type env sexpr)
      (symbol? sexpr)  :symbol

      (char?    sexpr) :literal
      (number?  sexpr) :literal
      (string?  sexpr) :literal
      (keyword? sexpr) :literal
      (regex?   sexpr) :literal
      (nil?     sexpr) :literal

      (vector?  sexpr) :collection
      (set?     sexpr) :collection
      (map?     sexpr) :collection

      :else :unknown)))

(defmethod analyze :unknown [env sexpr]
  (log/warn "Could not analyze:" sexpr)
  {})

(defmethod analyze :literal [env sexpr]
  (let [spec (cond
               (char?    sexpr) char?
               (number?  sexpr) number?
               (string?  sexpr) string?
               (keyword? sexpr) keyword?
               (regex?   sexpr) regex?
               (nil?     sexpr) nil?)]
    {::a/spec spec
     ::a/original-expression sexpr}))

(defmethod analyze :symbol [env sym]
  (or (a/symbol-detail env sym) {}))

(defmethod analyze :function-call [env [function & arguments]]
  (let [{::a/keys [arities]} (a/function-detail env function)
        {::a/keys [gspec]} (get arities (count arguments) :n)
        {::a/keys [arg-specs]} gspec
        args-type-desc (mapv (partial analyze env) arguments)
        errors (mapcat
                 (fn [[arg-spec arg]]
                   (let [{::a/keys [spec]} (analyze env arg)]
                     (->> (grp.u/try-sampling {::a/return-spec spec})
                       ;; TODO: extract to utils?
                       (map (fn [sample]
                              (some->>
                                (s/explain-data arg-spec sample)
                                ::s/problems
                                (map (fn [e]
                                       {::a/original-expression arg
                                        ::a/expected (:pred e)
                                        ::a/actual (:val e)
                                        ::a/spec arg-spec})))))
                       (first))))
                 (map vector arg-specs arguments))]
    (cond-> (grp.fnt/calculate-function-type env function args-type-desc)
      (seq errors) (update ::a/errors concat errors))))

(defmethod analyze :do [env [_ & body]]
  (analyze
    (reduce (fn [env sexpr]
              (merge env (::a/errors (analyze env sexpr))))
      env (butlast body))
    (last body)))

(defmethod analyze :let [env [_ bindings & body]]
  (analyze (reduce (fn [env [bind-sym sexpr]]
                     (assoc-in env [::a/local-symbols bind-sym]
                       (analyze env sexpr)))
             env (partition 2 bindings))
    `(do ~@body)))
