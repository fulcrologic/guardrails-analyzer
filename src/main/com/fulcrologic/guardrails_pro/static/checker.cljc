(ns com.fulcrologic.guardrails-pro.static.checker
  #?(:cljs (:require-macros [com.fulcrologic.guardrails-pro.static.checker :refer [function-assertions >>defn]]))
  (:require
    #?(:clj [cljs.analyzer])
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn => >def]]
    [clojure.test.check.generators]
    [clojure.spec.gen.alpha :as gen]
    [taoensso.timbre :as log]
    [taoensso.encore :as enc]
    [clojure.walk :as walk])
  #?(:clj
     (:import [clojure.lang Cons])))

;; The Unknown type is special. It is never used to generate samples for heuristic checking, but instead is
;; an explicit statement that the type is unknown, could be anything, and you should give up on it.
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

(defn return-type [{::keys [context] :as env} form]
  (let [sym   (first form)
        sym   (get-in context [:global-symbols sym] sym)
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
        arity               (dec (count form))
        argument-expression (nth form (inc argument-ordinal))
        fname               (get-in context [:global-symbols fname] fname)]
    (if-let [registered-function (get-in env [::registry fname])]
      (let [arg-spec              (get-in registered-function [:arity arity :argument-types argument-ordinal])
            recognized-expression (recognize env argument-expression)
            literal?              (= Literal (type recognized-expression))
            argument-type         (typecheck env recognized-expression)]
        (if (and argument-type (not= ::Unknown argument-type) arg-spec)
          (try
            (let [samples  (cond
                             (= argument-type arg-spec) [::trivially-correct]
                             literal? [argument-expression]
                             ;; TODO: cache samples for a given spec?
                             :otherwise (try (gen/sample (s/gen argument-type))
                                             (catch #?(:cljs :default :clj Exception) e
                                               nil)))
                  _        (when-not (seq samples)
                             (record-error! env call-node (str "Sample generation failed. Please add a generator for " (s/describe argument-type))))
                  ;; TODO: The preamble can be partially generated by `record-error!`
                  preamble (str file ":" line ": " "Argument " argument-ordinal " of " (pr-str (.-form call-node)) " should have type " (s/describe arg-spec) ".\nHowever, the expression ->" (pr-str argument-expression) "<-")
                  errors   (reduce (fn [result sample]
                                     (cond
                                       (= sample ::trivially-correct) (reduced result)
                                       (s/valid? arg-spec sample) result
                                       :else (let [message (if literal?
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
  (if-let [t (return-type env (.-form c))]
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

(defn bind-argument-types [{::keys [context] :as env} arity]
  (let [{:keys [argument-list argument-types]} (get-in context [:arity arity])]
    (reduce-kv
      (fn [env2 s t]
        ;; TODO: destructuring support
        (if (symbol? s)
          (do
            (log/debug "Binding" [s t])
            (bind-type env2 s t))
          env2))
      env
      (zipmap argument-list argument-types))))

#?(:clj
   (defn function-assertions* [env** form** sym args fspec & body]
     (let [specs              (remove #(= % '=>) fspec)
           cljc-resolve       (fn [s]
                                (or
                                  (if (enc/compiling-cljs?)
                                    (some-> (cljs.analyzer/resolve-var env** s) :name)
                                    (some->> s (ns-resolve *ns*) (symbol)))
                                  s))
           fqsym              (cljc-resolve sym)
           resolved-signature (into []
                                (map (fn [s]
                                       (cond
                                         (keyword? s) s
                                         (sequential? s) `(~(cljc-resolve (first s)) ~@(rest s))
                                         :else (cljc-resolve s))))
                                specs)
           arity              (if (contains? (set args) '&) :n (count args))
           symbol-map         (atom {})
           _                  (walk/postwalk (fn [f]
                                               (when (symbol? f)
                                                 (swap! symbol-map assoc `(quote ~f) `(quote ~(cljc-resolve f)))))
                                body)
           ;; TODO: This is probably not quite right. This is extensible in only one dimension (which kind of
           ;; thing to check) and does not include the option of allowing pluggable additional checks per form.
           body-checks        (for [expr body]
                                `(let [info#    ~(merge (meta expr) (when-not (enc/compiling-cljs?) {:file *file*}))
                                       context# (get-in ~'env [::registry (quote ~fqsym)])
                                       env#     (bind-argument-types (merge ~'env info# {::context context#}) ~arity)]
                                   (typecheck env# (recognize env# (quote ~expr)))))]
       `(do
          (register! {:name           (quote ~fqsym)
                      :meta           ~(meta form**)
                      ;; NOTE: we put speculative global resolution of all simple symbols
                      ;; in here. Then when we process `let` at runtime we'll know if there is a simple symbol
                      ;; in env and can prefer that, but we can use this to look up the resolved global name if necessary.
                      :global-symbols ~(deref symbol-map)
                      :typevars       ~(-> fspec meta :typevars)
                      :checks         (fn [~'env] ~@body-checks)
                      :arity          {~arity {:argument-types ~(vec (butlast resolved-signature))
                                               :argument-list  (quote ~args)
                                               :return-type    ~(last resolved-signature)}}})))))

(defmacro function-assertions
  "A simulation of what >defn would do. Basically it would emit the defn, the spec, and then emit a call to `register!`
   to register the data about the function with the type checker."
  [sym args fspec & body]
  (let [specs              (remove #(= % '=>) fspec)
        current-ns         (if (enc/compiling-cljs?) (-> &env :ns :name name) (name (ns-name *ns*)))
        cljc-resolve       (fn [s]
                             (or
                               (if (enc/compiling-cljs?)
                                 (some-> (cljs.analyzer/resolve-var &env s) :name)
                                 (some->> s (ns-resolve *ns*) (symbol)))
                               s))
        fqsym              (cljc-resolve sym)
        resolved-signature (into []
                             (map (fn [s]
                                    (if (keyword? s)
                                      s
                                      (cljc-resolve s))))
                             specs)
        arity              (if (contains? (set args) '&) :n (count args))
        symbol-map         (atom {})
        _                  (walk/postwalk (fn [f]
                                            (when (symbol? f)
                                              (swap! symbol-map assoc `(quote ~f) `(quote ~(cljc-resolve f)))))
                             body)
        ;; TODO: This is probably not quite right. This is extensible in only one dimension (which kind of
        ;; thing to check) and does not include the option of allowing pluggable additional checks per form.
        body-checks        (for [expr body]
                             `(let [info#    ~(merge (meta expr) (when-not (enc/compiling-cljs?) {:file *file*}))
                                    context# (get-in ~'env [::registry (quote ~fqsym)])
                                    env#     (bind-argument-types (merge ~'env info# {::context context#}) ~arity)]
                                (typecheck env# (recognize env# (quote ~expr)))))]
    `(do
       (register! {:name           (quote ~fqsym)
                   :meta           ~(meta &form)
                   ;; NOTE: we put speculative global resolution of all simple symbols
                   ;; in here. Then when we process `let` at runtime we'll know if there is a simple symbol
                   ;; in env and can prefer that, but we can use this to look up the resolved global name if necessary.
                   :global-symbols ~(deref symbol-map)
                   :checks         (fn [~'env] ~@body-checks)
                   :arity          {~arity {:argument-types ~(vec (butlast resolved-signature))
                                            :argument-list  (quote ~args)
                                            :return-type    ~(last resolved-signature)}}}))))

(defmacro >>defn [sym arglist spec & body]
  `(do
     (defn ~sym ~arglist ~@body)
     ~(apply function-assertions* &env &form sym arglist spec body)))

(defn check [sym]
  (let [target (get-in @registry [sym])
        checks (get target :checks)
        nspc   (some-> target :name namespace)
        env    (assoc (build-env) :file nspc
                                  :line (-> target :meta :line))]
    (when checks
      (checks env))
    @(::errors env)))

(comment
  (>>defn add [a b]
    [int? int? => int?]
    (+ a b))

  (>>defn prt [v]
    [string? => nil?]
    (println v)
    12)

  (>>defn f [x]
    [int? => int?]
    (let [a "hello world"
          b (add a "blah")]
      (prt b)
      (prt x))))

(comment

  (check `f)

  )
;; >defn would output code to register a function's type, along with a sequence of checks that should be run
;; once code reload is complete. This macro just simulates the steps to get the registration.
#_(function-assertions prt [a] [string? => string?]
    (noop b))
#_(function-assertions f [a] [string? => string?]
    (prt a)
    "Hello")
#_(function-assertions add [a b] [int? int? => int?]
    (prt a)
    (prt (f b)))


(comment
  (log/set-level! :info)
  (reset! registry {})
  (let [target (get-in @registry [`add])
        checks (get target :checks)
        nspc   (some-> target :name namespace)
        env    (assoc (build-env) :file nspc
                                  :line (-> target :meta :line))]
    (checks env)
    (doseq [{:keys [message]} @(::errors env)]
      (println message))
    ))
