(ns com.fulcrologic.guardrails-pro.static.analyzer
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.utils :as grp.u]
    [taoensso.timbre :as log])
  (:import
    #?@(:clj [(java.util.regex Pattern)])))

(defn regex? [x]
  #?(:clj (= (type x) Pattern)
     :cljs (regexp? x)))

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
    (grp.art/update-location (meta sexpr))
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

(defn analyze-hashmap! [env hashmap]
  (doseq [[k v] hashmap]
    (when-let [spec (s/get-spec k)]
      (let [{::grp.art/keys [samples]} (analyze! env v)]
        (when-let [failing-sample (and (seq samples)
                                    (some (fn _invalid-sample [sample]
                                            (when-not (s/valid? spec sample) sample))
                                      samples))]
          (grp.art/record-error! env
            {::grp.art/original-expression hashmap
             ::grp.art/expected {::grp.art/spec spec}
             ::grp.art/actual {::grp.art/failing-samples [failing-sample]}
             ::grp.art/message (str "Value in map: " v " failed to pass spec for " k " on failing sample " failing-sample ".")})))))
  {::grp.art/samples [hashmap]
   ::grp.art/type    "literal-hashmap"})

(defmethod analyze-mm :collection [env coll]
  (cond
    (map? coll) (analyze-hashmap! env coll)
    ;; TODO
    :else       {}))

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
              ;; TASK: update location & test
              (reduce-kv grp.art/remember-local
                env (grp.u/destructure* env bind-sexpr
                      (analyze! env sexpr))))
      env (partition 2 bindings))
    body))

(defmethod analyze-mm 'let [env sexpr] (analyze-let-like-form! env sexpr))
(defmethod analyze-mm 'clojure.core/let [env sexpr] (analyze-let-like-form! env sexpr))
(defmethod analyze-mm 'cljs.core/let [env sexpr] (analyze-let-like-form! env sexpr))
