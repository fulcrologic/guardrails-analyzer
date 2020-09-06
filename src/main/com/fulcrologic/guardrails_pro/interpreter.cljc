(ns com.fulcrologic.guardrails-pro.interpreter
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators]
    [com.fulcrologic.guardrails-pro.ftags.clojure-core]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.static.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.utils :as grp.u]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [taoensso.timbre :as log]))

(>defn bind-type-desc
  [env typename clojure-spec err]
  [::grp.art/env ::grp.art/type ::grp.art/spec map? => ::grp.art/type-description]
  (let [samples (grp.sampler/try-sampling! env (s/gen clojure-spec) err)]
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
          env (grp.u/destructure* env bind-sexpr
                (bind-type-desc env arg-type arg-spec
                  {::grp.art/original-expression arglist}))))
      env
      (map vector arglist arg-types arg-specs))))

(>defn check-return-type! [env {::grp.art/keys [body gspec]} {::grp.art/keys [samples]}]
  [::grp.art/env ::grp.art/arity-detail ::grp.art/type-description => any?]
  (let [{::grp.art/keys [return-spec return-type]} gspec
        sample-failure (some #(when-not (s/valid? return-spec %) {:failing-case %}) samples)]
    (when (contains? sample-failure :failing-case)
      (let [sample-failure (:failing-case sample-failure)]
        (grp.art/record-error! env
          {::grp.art/original-expression (last body)
           ::grp.art/actual              {::grp.art/failing-samples [sample-failure]}
           ::grp.art/expected            {::grp.art/type return-type ::grp.art/spec return-spec}
           ::grp.art/message             (str "Return value (e.g. " (pr-str sample-failure) ") does not always satisfy the return spec of " return-type ".")})))))

(>defn check!
  ([sym]
   [qualified-symbol? => any?]
   (check! (grp.art/build-env) sym))
  ([env sym]
   [::grp.art/env qualified-symbol? => any?]
   (let [{::grp.art/keys [last-changed last-checked] :as fd} (grp.art/function-detail env sym)]
     (log/spy :info [sym last-changed last-checked])
     (when (> last-changed (or last-checked 0))
       (grp.art/set-last-checked! env sym (grp.art/now-ms))
       (grp.art/clear-problems! sym)                        ;; TODO: needs to control proper env b/c clj vs cljs and target
       (let [{::grp.art/keys [arities extern-symbols location]} fd
             env (assoc env
                   ::grp.art/location location
                   ::grp.art/checking-sym sym
                   ::grp.art/extern-symbols extern-symbols)]
         (doseq [arity (keys arities)]
           (let [{::grp.art/keys [body] :as arity-detail} (get arities arity)
                 env    (bind-argument-types env arity-detail)
                 result (grp.ana/analyze-statements! env body)]
             (log/info "Locals for " sym ":" (::grp.art/local-symbols env))
             (check-return-type! env arity-detail (log/spy :info result)))))))))

;; TODO: Daemon needs to watch the filesystem and publish that info to checkers when files change, appear, or disappear
(defn check-all! []
  (let [env (grp.art/build-env)]
    (doseq [[fn-sym fd] (::grp.art/registry env)]
      (check! env fn-sym))))

;;TODO: defn force-check-all! clear all cache & restart
