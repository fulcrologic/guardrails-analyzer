;; Copyright (c) Fulcrologic, LLC. All rights reserved.
;;
;; Permission to use this software requires that you
;; agree to our End-user License Agreement, legally obtain a license,
;; and use this software within the constraints of the terms specified
;; by said license.
;;
;; You may NOT publish, redistribute, or reproduce this software or its source
;; code in any form (printed, electronic, or otherwise) except as explicitly
;; allowed by your license agreement..

(ns com.fulcrologic.copilot.analysis.function-type
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as cp.ana.disp]
    [com.fulcrologic.copilot.analysis.destructuring :as cp.destr]
    [com.fulcrologic.copilot.analysis.sampler :as cp.sampler]
    [com.fulcrologic.copilot.analysis.spec :as cp.spec]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.guardrails.core :refer [=> >defn]]))

(>defn bind-type-desc
  [env typename clojure-spec err]
  [::cp.art/env ::cp.art/type ::cp.art/spec map? => ::cp.art/type-description]
  (let [samples (cp.sampler/try-sampling! env (cp.spec/generator env clojure-spec) err)]
    (cond-> {::cp.art/spec clojure-spec
             ::cp.art/type typename}
      (seq samples) (assoc ::cp.art/samples samples))))

(>defn bind-argument-types
  [env arglist gspec]
  [::cp.art/env vector? ::cp.art/gspec => ::cp.art/env]
  (let [{::cp.art/keys [argument-types argument-specs]} gspec]
    (reduce
      (fn [env [bind-sexpr argument-type argument-spec]]
        (reduce-kv cp.art/remember-local
          env (cp.destr/destructure! env bind-sexpr
                (bind-type-desc env argument-type argument-spec
                  {::cp.art/original-expression bind-sexpr}))))
      env (map vector (remove #{'&} arglist) argument-types argument-specs))))

(>defn check-return-type!
  ([env gspec {:as td ::cp.art/keys [original-expression]}]
   [::cp.art/env ::cp.art/gspec ::cp.art/type-description => any?]
   (check-return-type! env gspec td original-expression))
  ([env {::cp.art/keys [return-type return-spec]} type-description original-expression]
   [::cp.art/env ::cp.art/gspec ::cp.art/type-description ::cp.art/original-expression => any?]
   (if (cp.art/path-based? type-description)
     ;; Path-based: validate each path separately and track which fail
     (let [paths         (::cp.art/execution-paths type-description)
           failing-paths (keep (fn [path]
                                 (let [samples        (::cp.art/samples path)
                                       failing-sample (some #(when-not (cp.spec/valid? env return-spec %)
                                                               %)
                                                        samples)]
                                   (when failing-sample
                                     (assoc path ::cp.art/failing-sample failing-sample))))
                           paths)]
       (when (seq failing-paths)
         (cp.art/record-error! env
           {::cp.art/original-expression original-expression
            ::cp.art/actual              {::cp.art/failing-samples (set (map ::cp.art/failing-sample failing-paths))
                                          ::cp.art/failing-paths   failing-paths}
            ::cp.art/expected            #::cp.art{:spec return-spec :type return-type}
            ::cp.art/problem-type        :error/bad-return-value})))
     ;; Non-path-based: validate all samples together (backward compatible)
     (let [samples        (::cp.art/samples type-description)
           sample-failure (some #(when-not (cp.spec/valid? env return-spec %)
                                   {:failing-case %})
                            samples)]
       (when (contains? sample-failure :failing-case)
         (let [sample-failure (:failing-case sample-failure)]
           (cp.art/record-error! env
             {::cp.art/original-expression original-expression
              ::cp.art/actual              {::cp.art/failing-samples #{sample-failure}}
              ::cp.art/expected            #::cp.art{:spec return-spec :type return-type}
              ::cp.art/problem-type        :error/bad-return-value})))))))

(>defn validate-argtypes!?
  [env {::cp.art/keys [arglist gspec]} argtypes]
  [::cp.art/env ::cp.art/arity-detail (s/coll-of ::cp.art/type-description) => boolean?]
  (if (some ::cp.art/unknown-expression argtypes)
    false                                                   ;; TASK: tests
    (let [failed?     (atom false)
          get-samples (comp set (partial cp.sampler/get-args env))
          {::cp.art/keys [argument-types argument-specs argument-predicates]} gspec]
      (when-not (or (some #{'&} arglist)
                  (= (count arglist) (count argtypes)))
        (reset! failed? true)
        (cp.art/record-error! env
          #::cp.art{:original-expression (map ::cp.art/original-expression argtypes)
                    :problem-type        :error/invalid-function-arguments-count}))
      (when-not @failed?
        (let [[syms specials] (split-with (complement #{:as '&}) arglist)]
          (doseq [[arg-sym argument-type argument-spec [samples original-expression]]
                  (map vector syms argument-types argument-specs
                    (map (juxt get-samples ::cp.art/original-expression) argtypes))
                  :let [checkable? (and argument-spec (seq samples))]]
            (when-not checkable?
              (cp.art/record-warning! env original-expression :warning/unable-to-check))
            (when-let [{:keys [failing-sample]}
                       (and checkable?
                         (some (fn _invalid-sample [sample]
                                 (when-not (cp.spec/valid? env argument-spec sample)
                                   {:failing-sample sample}))
                           samples))]
              (reset! failed? true)
              (cp.art/record-error! env
                {::cp.art/original-expression original-expression
                 ::cp.art/expected            #::cp.art{:spec argument-spec :type argument-type}
                 ::cp.art/actual              {::cp.art/failing-samples #{failing-sample}}
                 ::cp.art/problem-type        :error/function-argument-failed-spec
                 ::cp.art/message-params      {:argument arg-sym}})))
          (doseq [:when (some #{'&} specials)
                  :let [rest-argtypes (seq (drop (count syms) argtypes))]
                  :when (seq rest-argtypes)
                  :let [rst-sym   (get (apply hash-map specials) '& nil)
                        args-spec (first (drop (count syms) argument-specs))
                        args-type (first (drop (count syms) argument-types))]
                  sample-rest-arguments (apply map vector (map get-samples rest-argtypes))
                  :when (not (cp.spec/valid? env args-spec sample-rest-arguments))]
            (reset! failed? true)
            (cp.art/record-error! env
              #::cp.art{:original-expression (map ::cp.art/original-expression rest-argtypes)
                        :actual              {::cp.art/failing-samples #{sample-rest-arguments}}
                        :expected            #::cp.art{:spec args-spec :type args-type}
                        :problem-type        :error/function-arguments-failed-spec}))))
      (when (and (not @failed?) (seq argtypes))
        (doseq [sample-arguments (apply map vector (map get-samples argtypes))
                argument-pred    argument-predicates]
          (when-not (apply argument-pred sample-arguments)
            (reset! failed? true)
            (cp.art/record-error! env
              {::cp.art/original-expression (map ::cp.art/original-expression argtypes)
               ::cp.art/actual              {::cp.art/failing-samples #{sample-arguments}}
               ::cp.art/expected            {::cp.art/spec argument-pred}
               ::cp.art/problem-type        :error/function-arguments-failed-predicate}))))
      (not @failed?))))

(defn valid-argtypes? [env arity argtypes]
  (with-redefs [cp.art/record-error! (constantly nil)]
    (validate-argtypes!? env arity argtypes)))

(s/def ::partial-argtypes (s/coll-of ::cp.art/type-description))

(>defn analyze-function-call! [env function argtypes]
  [::cp.art/env (s/or :function ::cp.art/function :lambda ::cp.art/lambda :invalid map?)
   (s/coll-of ::cp.art/type-description)
   => ::cp.art/type-description]
  (cond
    (::cp.art/unknown-expression function)
    #_=> function
    (not (::cp.art/arities function))
    #_=> (cp.ana.disp/unknown-expr env function)
    :else
    (let [{::cp.art/keys [arities] ::keys [partial-argtypes]} function
          argtypes      (concat partial-argtypes argtypes)
          {::cp.art/keys [gspec] :as arity} (cp.art/get-arity arities argtypes)
          {::cp.art/keys [return-type return-spec]} gspec
          function-type #::cp.art{:type return-type :spec return-spec}]
      (assoc function-type ::cp.art/samples
                           (if (validate-argtypes!? env arity argtypes)
                             (cp.sampler/sample! env function argtypes)
                             (cp.sampler/try-sampling! env (cp.spec/generator env return-spec)
                               {::cp.art/original-expression function}))))))

(>defn sample->type-description [sample]
  [any? => ::cp.art/type-description]
  {::cp.art/samples             #{sample}
   ::cp.art/original-expression sample})

(>defn validate-arguments-predicate!? [env arity & args]
  [::cp.art/env ::cp.art/arity-detail (s/+ any?) => boolean?]
  (validate-argtypes!? env arity
    (map sample->type-description args)))
