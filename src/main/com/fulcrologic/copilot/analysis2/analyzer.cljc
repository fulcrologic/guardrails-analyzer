(ns com.fulcrologic.copilot.analysis2.analyzer
  (:require
    clojure.test.check.generators
    [com.fulcrologic.guardrails.core :refer [>defn => |]]
    com.fulcrologic.copilot.analysis.fdefs.clojure-core
    com.fulcrologic.copilot.analysis.fdefs.clojure-spec-alpha
    com.fulcrologic.copilot.analysis.fdefs.clojure-string
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as d]
    [com.fulcrologic.copilot.analysis.function-type :as cp.fnt]
    [com.fulcrologic.copilot.analysis.analyzer.literals :as literals]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.reader :as cp.reader]
    [com.fulcrologic.copilot.analysis.spec :as cp.spec]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(declare check-form)

(defn returning
  "Place the given type descriptor into the env as the current expression's result."
  [env return-type]
  (assoc env ::expression-result return-type))

(defn errors
  "Returns all of the errors currently tracked in `env`."
  [env] (::errors env))

(defn with-error
  "Add a single error to env. `expr` should be the original-expression (e.g. wrapped literials) so there
   is position metadata."
  ([env expr problem-type]
   (with-error env expr nil nil nil problem-type))
  ([env expr failure spec type problem-type]
   (update env ::errors (fnil conj [])
     (cond-> {::cp.art/original-expression expr
              ::cp.art/problem-type        problem-type}
       failure (assoc ::cp.art/actual {::cp.art/failing-samples #{failure}})
       (and spec type) (assoc ::cp.art/expected #::cp.art{:spec spec :type type})))))

(defn check-return-type
  "Check that the current env's expression result is OK with respect to the given gspec. The
   `original-expression` should be the symbol of the function so that location metadata is available."
  [env {::cp.art/keys [return-type return-spec]} original-expression]
  (let [{::cp.art/keys [samples]} (::expression-result env)
        sample-failure (some #(when-not (cp.spec/valid? env return-spec %)
                                {:failing-case %})
                         samples)]
    (if (contains? sample-failure :failing-case)
      (let [sample-failure (:failing-case sample-failure)]
        (-> env
          (with-error original-expression sample-failure return-spec return-type
            :error/bad-return-value))))))

(defn analyze-statements!
  "Analyze a sequence of statements where only the last one's result is kept in the env's result
   expression; however, it collects any errors from the rest of the body into the returned envs."
  [env body]
  (let [body-env (assoc env
                   ::errors (reduce
                              (fn [errs expr]
                                (let [envs (check-form env expr)]
                                  (reduce
                                    (fn [errs env] (into errs (errors env)))
                                    errs
                                    envs)))
                              []
                              (butlast body)))]
    (if-not (last body)
      [(with-error env body :warning/empty-body)]
      (check-form body-env (last body)))))

(defn analyze-single-arity! [env defn-sym [arglist _ & body]]
  (let [gspec       (get-in (cp.art/function-detail env defn-sym)
                      [::cp.art/arities (count arglist) ::cp.art/gspec])
        env         (cp.fnt/bind-argument-types env arglist gspec)
        result-envs (analyze-statements! env body)]
    (map #(check-return-type % gspec defn-sym) result-envs)))

(defn analyze:>defn! [env [_ defn-sym & defn-forms :as sexpr]]
  (let [env     (assoc env ::cp.art/checking-sym defn-sym)
        arities (drop-while (some-fn string? map?) defn-forms)]
    (if (vector? (first arities))
      (analyze-single-arity! env defn-sym arities)
      (mapcat #(analyze-single-arity! env defn-sym %) arities))))

(defmulti check-form
  "Check a form. This multimethod responds to dispatches on the form type via
   `d/analyze-dispatch`, and serves as the primary extension point for all analysis.

   `env` - Checker env
   `form` - The form to be checked, which should have been read by copilot.reader

   Returns a sequence of `env`s, each with updated bindings (possibly narrowed), return expressions,
   and errors.

   The total collection of all errors reflects the errors found across all splits of the
   exploration space.

   The returned envs represent results from different code paths through the exploration space."
  (fn [env form] (d/analyze-dispatch env form)))

(defmethod check-form :literal/wrapped [env {:keys [kind value] :as orig}]
  (let [missing-spec?   (and (qualified-keyword? value)
                          (not (cp.spec/lookup env value)))
        lit-kind        (if-not (namespace kind)
                          (keyword (namespace ::_) (name kind))
                          kind)
        type-descriptor (literals/literal-td env lit-kind value orig)]
    [(cond-> (returning env type-descriptor)
       missing-spec? (with-error orig :warning/qualified-keyword-missing-spec))]))

(defmethod check-form :default [env form]
  (log/error "Cannot analyze" form)
  [env])
(defmethod check-form `com.fulcrologic.guardrails.core/>defn [env form]
  (analyze:>defn! env form))
(defmethod check-form `com.fulcrologic.guardrails.core/>defn- [env form]
  (analyze:>defn! env form))


(defn check!
  "Check a file.

   The namespace related to the file must be loaded, and guardrails must be in :copilot mode.

   Returns a sequence of error descriptors."
  [file]
  (let [forms (cp.reader/read-file file :clj)
        env   (cp.art/build-env forms)
        {:keys [forms file aliases refers]} forms]
    (mapcat
      (fn [envs]
        (map errors envs))
      (for [form forms
           env  [env]]
       (check-form env form)))

    #_(cp.analytics/profile ::check!
        (cp.spec/with-empty-cache
          #?(:cljs (fn check-forms! [[form & forms]]
                     (if-not form (on-done)
                                  (js/setTimeout
                                    (fn []
                                      (check-form! env form)
                                      (check-forms! forms))
                                    100)))
             :clj  (fn [forms]
                     (doseq [form forms]
                       (check-form! env form))
                     (on-done)))
          forms))
    ))

(comment
  (tap> (check-form (cp.art/build-env [])
          (-> (cp.reader/read-file (java.io.StringReader. "(ns app) \"\"") :clj)
            :forms first)
          ))
  (and (map? sexpr) (:com.fulcrologic.copilot/meta-wrapper? sexpr)) :literal/wrapped
  (check! "/home/tony/fulcrologic/copilot/src/dev/sample.clj"))