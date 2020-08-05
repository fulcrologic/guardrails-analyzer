(ns com.fulcrologic.guardrails-pro.interpreter
  "DEPRECATED. KEEPING AROUND TO STEAL IDEAS FROM."
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn => | ?]]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [clojure.test.check.generators]
    [clojure.spec.gen.alpha :as gen]
    [taoensso.timbre :as log]
    [clojure.spec.alpha :as s]
    [clojure.string :as str])
  #?(:clj
     (:import [clojure.lang Cons])))

;; The traversal of the code should be an (extensible) pass, but the actions taken at a given step of that pass
;; should also be extensible. Both dimensions of extension should carry along and expand an `env`.

;; This is going to be a reduction. We're not actually side-effecting into RAM or other things
;; but are instead gathering:
;;
;; * bindings with samples
;; * Warnings and errors (attached to the form/info that caused the problem)
;;
;; The interpretation is a naturally recursive algorithm, but is accumulation into a
;; linear data structure? Perhaps we turn the forms into something more akin to an
;; AST and then traverse that?

(s/def ::form list?)
(s/def ::value any?)
(s/def ::type #{:symbol :map :call :primitive :unknown})
(s/def ::sym symbol?)
(defmulti node-spec ::type)
(defmethod node-spec :symbol [_] (s/keys :req [::type ::sym]))
(defmethod node-spec :map [_] (s/keys :req [::type ::value]))
(defmethod node-spec :call [_] (s/keys :req [::type ::form]))
(defmethod node-spec :primitive [_] (s/keys :req [::type ::value]))
(defmethod node-spec :default [_] (s/keys :req [::type]))
(s/def ::node (s/multi-spec node-spec ::type))

(defn ->Primitive [v] {::type :primitive ::value v})
(defn ->AMap [v] {::type :map ::value v})
(defn ->ASymbol [v] {::type :symbol ::sym v})
(defn ->Call [v] {::type :call ::form v})

(>defn primitive?
  [x]
  [any? => boolean?]
  (boolean
    (or
      (nil? x)
      (boolean? x)
      (string? x)
      (double? x)
      (int? x))))

(>defn recognize
  "Recognize and convert the given `expr` into an Expression node."
  [env expr]
  [::a/env any? => ::node]
  (cond
    (map? expr) (->AMap expr)
    (primitive? expr) (->Primitive expr)
    (or #?(:clj (instance? Cons expr)) (list? expr)) (->Call (apply list expr))
    (symbol? expr) (->ASymbol expr)
    :else {::type :unknown}))

(defmulti typecheck-mm
  "Type check a general expression. This method dispatches on the type of `node`. Predefined node types include
   `Primitive` and `Call`."
  (fn [env node] (::type node)))

(>defn typecheck
  "Given the env of the node's context and a node: return the type description of that node."
  [env node]
  [::a/env ::node => ::a/type-description]
  (typecheck-mm env node))

(defmethod typecheck-mm :default [env form] {})

(defn record-error! [env node detail-message]
  (log/debug "Recording error")
  (swap! (::errors env) conj {:node    node
                              :env     env
                              :message detail-message}))

(defn error-messages [env]
  (mapv :message (some-> env ::errors deref)))

(defn extern-type [{::a/keys [extern-symbols] :as env} sym]
  ;; TASK: need some registry action here, where we look up syms to see if we have a spec for them.
  ;; A symbol could be a function, in which case the return type is of higher-order.
  (let [extern-value (get-in extern-symbols [sym ::a/value])]
    (when-not (or (nil? extern-value) (fn? extern-value))
      {::a/samples [extern-value]})))

(defmethod typecheck-mm :symbol [env s]
  (let [sym         (::sym s)
        local-type  (get-in env [::a/local-symbols sym])
        extern-type (extern-type env sym)]
    (or local-type extern-type {})))

#_(defmethod typecheck-mm Call [env ^Call c]
    (log/debug "Checking call" (.-form c))
    (typecheck-call env c))

(def build-env a/build-env)

;; LANDMARK: make this general util somewhere
(>defn try-sampling
  "Returns a sequence of samples, or nil if the type cannot be sampled."
  [type]
  [::a/spec => (? (s/coll-of any? :min-count 1))]
  (try
    (gen/sample (s/gen type))
    (catch #?(:clj Exception :cljs :default) _
      (log/info "Cannot sample" type)
      nil)))

(defn bind-type
  "Returns a new `env` with the given sym bound to the known type."
  [env sym typename clojure-spec]
  (let [samples (try-sampling clojure-spec)]
    (assoc-in env [::a/local-symbols sym] (cond-> {::a/spec clojure-spec
                                                   ::a/type typename}
                                            (seq samples) (assoc ::a/samples samples)))))

(defn check-argument! [{:keys  [file line]
                        ::keys [context] :as env} call-node argument-ordinal]
  (let [form                (::form call-node)
        fname               (first form)
        arity               (log/spy :debug (dec (count form)))
        argument-expression (nth form (inc argument-ordinal))
        fname               (log/spy :debug (get-in context [:global-symbols fname] fname))]
    (if-let [registered-function (log/spy :debug (get-in env [::a/registry fname]))]
      (let [arg-spec              (log/spy :debug (get-in registered-function [:arity arity :argument-types (log/spy :debug argument-ordinal)]))
            recognized-expression (recognize env argument-expression)
            literal?              (= :primitive (::type recognized-expression))
            argument-type         (log/spy :debug (typecheck env (log/spy :debug recognized-expression)))]
        (if (log/spy :debug (and argument-type (not= ::Unknown argument-type) arg-spec))
          (try
            (let [samples  (log/spy :debug (if literal? [argument-expression] (try (gen/sample (s/gen argument-type))
                                                                                   (catch #?(:cljs :default :clj Exception) e
                                                                                     nil))))
                  _        (when-not (seq samples)
                             (record-error! env call-node (str "Sample generation failed. Please add a generator for " (s/describe argument-type))))
                  ;; TODO: The preamble can be partially generated by `record-error!`
                  preamble (str file ":" line ": " "Argument " argument-ordinal " of " (pr-str (.-form call-node)) " should have type " (s/describe arg-spec) ".\nHowever, the expression ->" (pr-str argument-expression) "<-")
                  errors   (reduce (fn [result sample]
                                     (if (s/valid? (log/spy :debug arg-spec) (log/spy :debug sample))
                                       result
                                       (let [message (if literal?
                                                       (str preamble " has type " (s/describe argument-type))
                                                       (str preamble " has an incompatible type of " (s/describe argument-type)))]
                                         (reduced (conj result message)))))
                             []
                             samples)]
              (when (seq errors)
                (record-error! env call-node (first errors)))
              argument-type)
            (catch #?(:clj Exception :cljs :default) e
              (log/error "Type checker threw an unexpected exception checking argument" argument-ordinal "of" form ":" (ex-message e))
              argument-type))
          (do
            (log/debug "Skipping argument check for" form argument-expression ". Argument's type is unknown.")
            ::Unknown)))
      (do
        (log/debug "Skipping argument check. Function not in registry: " fname)
        ::Unknown))))

(defmethod typecheck-mm :primitive [env node]
  (let [literal (::value node)]
    (cond
      (int? literal) {::a/spec    int?
                      ::a/type    "int?"
                      ::a/samples (try-sampling int?)}
      (double? literal) {::a/spec    double?
                         ::a/type    "double?"
                         ::a/samples (try-sampling double?)}
      (string? literal) {::a/spec    string?
                         ::a/type    "string?"
                         ::a/samples (try-sampling string?)}
      :else {})))

#_(defmethod typecheck-call :default [env c]
    (doseq [ordinal (range (arity c))]
      (log/debug "Checking argument" ordinal)
      (check-argument! env c ordinal))
    (if-let [t (log/spy :info (return-type env (.-form c)))]
      t
      ::Unknown))

(defn typecheck-let [env c]
  (let [form        (::form c)
        bindings    (partition 2 (second form))
        body        (drop 2 form)
        return-type (typecheck env (recognize env (last form)))
        body-env    (reduce (fn [env [name value]]
                              (let [{::a/keys [spec type]} (typecheck env (recognize env value))]
                                ;; TODO: Process destructured syms, including if the spec of `type` says it will return
                                ;; the keys you're expecting on map destructure?
                                (if (symbol? name)
                                  (bind-type env name type spec)
                                  env)))
                      env
                      bindings)]
    (doseq [expr body]
      (typecheck body-env (recognize body-env expr)))
    return-type))

;(defmethod typecheck-call 'clojure.core/let [env c] (typecheck-let env c))
;(defmethod typecheck-call 'cljs.core/let [env c] (typecheck-let env c))
;(defmethod typecheck-call 'let [env c] (typecheck-let env c))

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

(>defn check-return-type! [env {::a/keys [arglist body gspec]} {::a/keys [type samples]}]
  [::a/env ::a/arity-detail ::a/type-description => any?]
  (let [{expected-return-spec ::a/return-spec
         expected-return-type ::a/return-type} gspec
        location       (meta (last body))
        sample-failure (some #(when-not (s/valid? expected-return-spec %)
                                [%]) samples)]
    (when (seq sample-failure)
      (a/record-problem! (::checking env) location
        (str "Return value (e.g. " (pr-str (first sample-failure)) ") does not always satisfy the return spec of " expected-return-type ".")))))

(defn check!
  "Run checks on the function named by the fully-qualified `sym`"
  [env sym]
  (let [{::a/keys [arities extern-symbols]} (get-in env [::a/registry sym])
        env (assoc env
              ::checking sym
              ::a/extern-symbols extern-symbols)
        ks  (keys arities)]
    (doseq [arity ks
            :let [{::a/keys [body] :as arity-detail} (get arities arity)
                  env (bind-argument-types env arity-detail)]]
      (let [result (last
                     (for [expr body
                           :let [node (recognize env expr)]]
                       (do (log/spy :info node)
                           (typecheck env node))))]
        (check-return-type! env arity-detail result)))))

;; TASK: We can implement a typecheck on literal maps that checks each key. Fully-nsed keys that have
;; no spec can be a low-level notification. The values that fail to match specs are real errors.

(comment
  (a/clear-problems!)
  (check! (build-env) 'com.fulcrologic.guardrails-pro.interpreter-spec/f)
  @a/memory


  (f (h (j 2)))

  ; Calculate the type description of 2
  ; Is td:2 a good arg for j? --> side-effect any problems to problem store
  ; What td:j (j's return type description)?
  ; Is td:j ok for h arg 1? --> side-effect any problems to problem store
  ; ...
  ;; So, to get the type of an arbitrary expression I have to *check* all nested expressions
  )