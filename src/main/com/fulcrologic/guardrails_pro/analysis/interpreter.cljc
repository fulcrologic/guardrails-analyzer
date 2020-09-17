(ns com.fulcrologic.guardrails-pro.analysis.interpreter
  (:require
    [clojure.test.check.generators]
    [com.fulcrologic.guardrails-pro.ftags.clojure-core]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [taoensso.timbre :as log]))

(>defn check!
  ([sym]
   [qualified-symbol? => any?]
   (check! (grp.art/build-env) sym))
  ([env sym]
   [::grp.art/env qualified-symbol? => any?]
   (if-let [{::grp.art/keys [last-changed last-checked] :as fd} (grp.art/function-detail env sym)]
     ;; TASK: Refresh logic is a lot more complex than this...disabling for now
     (do
       (log/spy :info [sym last-changed last-checked])
       (when true ; (> last-changed (or last-checked 0))
         (grp.art/set-last-checked! env sym (grp.art/now-ms))
         (grp.art/clear-problems! sym)                        ;; TODO: needs to control proper env b/c clj vs cljs and target
         (let [{::grp.art/keys [arities extern-symbols location]} fd
               env (assoc env
                     ::grp.art/location location
                     ::grp.art/checking-sym sym
                     ::grp.art/extern-symbols extern-symbols)]
           (doseq [{::grp.art/keys [body] :as arity-detail} (vals arities)]
             (let [env    (grp.fnt/bind-argument-types env arity-detail)
                   result (grp.ana/analyze-statements! env body)]
               (log/info "Locals for " sym ":" (::grp.art/local-symbols env))
               (grp.fnt/check-return-type! env arity-detail (log/spy :info result)))))))
     (log/error "Failed to find symbol in registry: " sym))))

;; TODO: Daemon needs to watch the filesystem and publish that info to checkers when files change, appear, or disappear
(defn check-all! []
  (grp.art/clear-bindings!)
  (let [env (grp.art/build-env)]
    (doseq [[fn-sym fd] (::grp.art/registry env)]
      (check! env fn-sym))))

;;TODO: defn force-check-all! clear all cache & restart
