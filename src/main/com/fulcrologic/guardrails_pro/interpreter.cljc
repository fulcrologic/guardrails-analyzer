(ns com.fulcrologic.guardrails-pro.interpreter
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.utils :as grp.u]
    [com.fulcrologic.guardrails-pro.static.analyzer :as grp.ana]
    [clojure.test.check.generators]
    [taoensso.timbre :as log]
    [clojure.spec.alpha :as s]))

(>defn bind-type-desc
  [typename clojure-spec]
  [::grp.art/type ::grp.art/spec => ::grp.art/type-description]
  (let [samples (grp.u/try-sampling {::grp.art/return-spec clojure-spec})]
    (cond-> {::grp.art/spec clojure-spec
             ::grp.art/type typename}
      (seq samples) (assoc ::grp.art/samples samples))))

(>defn bind-argument-types
  [env arity-detail]
  [::grp.art/env ::grp.art/arity-detail => ::grp.art/env]
  (let [{::grp.art/keys [gspec arglist]} arity-detail
        {::grp.art/keys [arg-specs arg-types]} gspec]
    (reduce
      (fn [env [bind-sexpr arg-type arg-spec]]
        (reduce-kv grp.art/remember-local
          env (log/spy (grp.u/destructure* env bind-sexpr
                (bind-type-desc arg-type arg-spec)))))
      env
      (map vector arglist arg-types arg-specs))))

(>defn check-return-type! [env {::grp.art/keys [gspec]} {::grp.art/keys [samples]}]
  [::grp.art/env ::grp.art/arity-detail ::grp.art/type-description => any?]
  (let [{expected-return-spec ::grp.art/return-spec
         expected-return-type ::grp.art/return-type} gspec
        sample-failure (some #(when-not (s/valid? expected-return-spec %) [%]) samples)]
    (when (seq sample-failure)
      (grp.art/record-error! env
        {::grp.art/actual (first sample-failure)
         ::grp.art/expected expected-return-type
         ::grp.art/message (str "Return value (e.g. " (pr-str (first sample-failure)) ") does not always satisfy the return spec of " expected-return-type ".")}))))

(>defn check! [env sym]
  [::env qualified-symbol? => any?]
  (log/info :check! sym (grp.art/function-detail env sym))
  (let [{::grp.art/keys [arities extern-symbols]} (grp.art/function-detail env sym)
        env (assoc env
              ::grp.art/extern-symbols extern-symbols
              ::grp.art/checking-sym sym)]
    (doseq [arity (keys arities)]
      (let [{::grp.art/keys [body] :as arity-detail} (get arities arity)
            env (bind-argument-types env arity-detail)
            ;; FIXME: should be metadata of the sym itself (not its arity)
            env (grp.art/update-location env (::grp.art/gspec arity-detail))
            result (grp.ana/analyze-statements! env body)]
        (log/info :arity-detail arity-detail)
        (log/info "Locals" (::grp.art/local-symbols env))
        (check-return-type! env arity-detail result)))))

(comment
  (do (grp.art/clear-problems!)
    (check! (grp.art/build-env) 'com.fulcrologic.guardrails-pro.core/env-test))
  )
