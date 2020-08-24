(ns com.fulcrologic.guardrails-pro.interpreter
  "DEPRECATED. KEEPING AROUND TO STEAL IDEAS FROM."
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn => | ?]]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.utils :as grp.u]
    [com.fulcrologic.guardrails-pro.static.analyzer :as analyze]
    [clojure.test.check.generators]
    [taoensso.timbre :as log]
    [clojure.spec.alpha :as s])
  #?(:clj
     (:import [clojure.lang Cons])))

(defn bind-type
  "Returns a new `env` with the given sym bound to the known type."
  [env sym typename clojure-spec]
  (let [samples (grp.u/try-sampling {::a/return-spec clojure-spec})]
    (log/info "Binding samples: " sym (vec samples))
    (assoc-in env [::a/local-symbols sym]
      (cond-> {::a/spec clojure-spec
               ::a/type typename}
        (seq samples) (assoc ::a/samples samples)))))


(>defn bind-argument-types
  [env arity-detail]
  [::a/env ::a/arity-detail => ::a/env]
  (let [{argument-list                    ::a/arglist
         {::a/keys [arg-specs arg-types]} ::a/gspec} arity-detail]
    (reduce
      (fn [env2 [sym arg-type arg-spec]]
        ;; TODO: destructuring support
        (if (symbol? sym)
          (bind-type env2 sym arg-type arg-spec)
          env2))
      env
      (map vector argument-list arg-types arg-specs))))

(>defn check-return-type! [env {::a/keys [body gspec]} {::a/keys [samples]}]
  [::a/env ::a/arity-detail ::a/type-description => any?]
  (let [{expected-return-spec ::a/return-spec
         expected-return-type ::a/return-type} gspec
        ;; TASK: integrate location into errors
        sample-failure (some #(when-not (s/valid? expected-return-spec %) [%]) samples)]
    (when (seq sample-failure)
      (a/record-problem! env
        (str "Return value (e.g. " (pr-str (first sample-failure)) ") does not always satisfy the return spec of " expected-return-type ".")))))

(defn check!
  "Run checks on the function named by the fully-qualified `sym`"
  [env sym]
  (let [{::a/keys [arities extern-symbols]} (get-in env [::a/registry sym])
        env (assoc env
              ;; TASK: track position ::a/checking sym
              ::a/extern-symbols extern-symbols)
        ks  (keys arities)]
    (doseq [arity ks
            :let [{::a/keys [body] :as arity-detail} (get arities arity)
                  env (bind-argument-types env arity-detail)]]
      (log/info "Locals" (::a/local-symbols env))
      (let [result (analyze/analyze-statements! env body)]
        (check-return-type! env arity-detail result)))))

(comment
  )
