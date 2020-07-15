(ns com.fulcrologic.guardrails-pro.interpreter
  "Code to interpret expressions from a function's body to detect if there are problems."
  (:require
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

(s/def ::Unknown any?)

(defonce registry (atom {}))

(defn register! [entry]
  (swap! registry assoc (:name entry) entry))

(defn literal? [x]
  (or
    (nil? x)
    (boolean? x)
    (string? x)
    (double? x)
    (int? x)))

;; ExpressionNode
(defprotocol ExpressionNode)
(deftype Literal [v] ExpressionNode)
(defprotocol ICall
  (arity [_] "Returns the arity of the call"))
(deftype Call [form] ExpressionNode
  ICall
  (arity [_] (dec (count form))))
(deftype ASymbol [sym] ExpressionNode)
(deftype Unknown [] ExpressionNode)

(defmulti typecheck-call
  "Type check a call `c`, which must have type `Call`. This multimethod dispatches based on the first argument of the
   call (the function to call). If you want to add custom handling for a particular symbol (e.g. `let`), then you can
   add a `defmethod` for this multimethod and define how to handle that case."
  (fn [env c] (first (.-form c))))

(defn return-type [env form]
  (let [sym   (first form)
        arity (dec (count form))]
    (get-in env [::registry sym :arity arity :return-type] ::Unknown)))

(defn recognize
  "Recognize and convert the given `expr` into an Expression node."
  [env expr]
  (cond
    (literal? expr) (Literal. expr)
    (or #?(:clj (instance? Cons expr)) (list? expr)) (Call. (apply list expr))
    (symbol? expr) (ASymbol. expr)
    :else (Unknown.)))

(defmulti typecheck
  "Type check a general expression. This method dispatches on the type of `node`. Predefined node types include
   `Literal` and `Call`."
  (fn [env node] (type node)))

(defmethod typecheck :default [env form] ::Unknown)

(defn record-error! [env node detail-message]
  (log/debug "Recording error")
  (swap! (::errors env) conj {:node    node
                              :env     env
                              :message detail-message}))

(defn error-messages [env]
  (mapv :message (some-> env ::errors deref)))

(defmethod typecheck ASymbol [env ^ASymbol s]
  (if-let [bound-type (get-in env [::bound-symbol-types (.-sym s)])]
    bound-type
    ::Unknown))

(defmethod typecheck Call [env ^Call c]
  (log/debug "Checking call" (.-form c))
  (typecheck-call env c))

(defn build-env
  ([]
   {:file      ""
    :line      1
    ::registry @registry
    ::warnings (atom [])
    ::errors   (atom [])})
  ([registry]
   {:file      ""
    :line      1
    ::registry registry
    ::warnings (atom [])
    ::errors   (atom [])}))

(defn parsing-context
  "Returns a new `env` with the file/line context set to the provided values"
  [env filename line]
  (assoc env :file filename :line line))

(defn bind-type
  "Returns a new `env` with the given sym bound to the known type."
  [env sym type]
  (assoc-in env [::bound-symbol-types sym] type))

(defn check-argument! [{:keys  [file line]
                        ::keys [context] :as env} ^Call call-node argument-ordinal]
  (let [form                (.-form call-node)
        fname               (first form)
        arity               (log/spy :debug (dec (count form)))
        argument-expression (nth form (inc argument-ordinal))
        fname               (log/spy :debug (get-in context [:global-symbols fname] fname))]
    (if-let [registered-function (log/spy :debug (get-in env [::registry fname]))]
      (let [arg-spec              (log/spy :debug (get-in registered-function [:arity arity :argument-types (log/spy :debug argument-ordinal)]))
            recognized-expression (recognize env argument-expression)
            literal?              (= Literal (type recognized-expression))
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

(defmethod typecheck Literal [env node & args]
  (let [literal (.-v node)]
    (cond
      (int? literal) int?
      (double? literal) double?
      (string? literal) string?
      :else ::Unknown)))

(defmethod typecheck-call :default [env ^Call c]
  (doseq [ordinal (range (arity c))]
    (log/debug "Checking argument" ordinal)
    (check-argument! env c ordinal))
  (if-let [t (log/spy :info (return-type env (.-form c)))]
    t
    ::Unknown))

(defn typecheck-let [env ^Call c]
  (let [form        (.-form c)
        bindings    (partition 2 (second form))
        body        (drop 2 form)
        return-type (typecheck env (recognize env (last form)))
        body-env    (reduce (fn [env [name value]]
                              (let [type (typecheck env (recognize env value))]
                                ;; TODO: Process destructured syms, including if the spec of `type` says it will return
                                ;; the keys you're expecting on map destructure?
                                (if (symbol? name)
                                  (bind-type env name type)
                                  env)))
                      env
                      bindings)]
    (doseq [expr body]
      (typecheck body-env (recognize body-env expr)))
    return-type))

(defmethod typecheck-call 'clojure.core/let [env c] (typecheck-let env c))
(defmethod typecheck-call 'cljs.core/let [env c] (typecheck-let env c))
(defmethod typecheck-call 'let [env c] (typecheck-let env c))

(defn bind-argument-types [{::keys [context] :as env} function-description]
  (let [{argument-list            :arglist
         {:keys [argument-specs]} :gspec} function-description]
    (reduce-kv
      (fn [env2 s t]
        ;; TODO: destructuring support
        (if (symbol? s)
          (do
            (log/debug "Binding" [s t])
            (bind-type env2 s t))
          env2))
      env
      (zipmap argument-list argument-specs))))

(defn check-return-type! [env {:keys [arglist gspec body] :as description} {:keys [type samples]}]
  (let [expected-return-spec (get gspec :return-spec)]
    (when
      (or
        ;; TASK: this isn't quite right yet
        (and (seq samples)
          (some #(not (s/valid? expected-return-spec %)) samples))
        (and
          (empty? samples)
          type
          (not= type ::Unknown)
          (not= type expected-return-spec)))
      (record-error! env description "Return value can fail the return spec."))))

(defn check!
  "Run checks on the function named by the fully-qualified `sym`"
  [env sym]
  (let [{:keys [defn extern-symbols] :as definition} (get-in env [::registry sym])
        env     (assoc env ::extern-symbols extern-symbols)
        arities (filter #(str/starts-with? (name %) "arity") (keys defn))]
    (doseq [arity arities
            :let [{:keys [arglist gspec body] :as description} (get defn arity)
                  env (bind-argument-types env description)]]
      (log/info "Checking" sym arity)
      (let [result (last
                     (for [expr body
                           :let [node (recognize env expr)]]
                       ;; TASK: Continue here. I've modified the expected return type of
                       ;; typecheck so that we expect a return :type or :samples
                       (typecheck env node)))]
        (check-return-type! env description result)))))