(ns com.fulcrologic.guardrails-pro.core
  (:require
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.static.forms :as forms]
    [taoensso.encore :as enc]
    [clojure.walk :as walk]
    [taoensso.timbre :as log]))

(try
  (require 'cljs.analyzer.api)
  (catch Exception _))

(defn cljc-resolve [env s]
  (if (enc/compiling-cljs?)
    (let [ast-node (cljs.analyzer.api/resolve env s)
          macro?   (boolean (:macro ast-node))]
      (when ast-node
        (cond-> {:name   `(quote ~(:name ast-node))
                 :op     (:op ast-node)
                 :macro? macro?}
          macro? (assoc :op :macro)
          (not macro?) (assoc :value s))))
    (if (contains? env s)
      {:name   `(quote ~s)
       :macro? false
       :op     :local}
      (let [sym-var (ns-resolve *ns* env s)
            cls?    (class? sym-var)
            macro?  (boolean (and (not cls?) (some-> sym-var meta :macro)))]
        (when sym-var
          (cond-> {:name   `(quote ~(symbol sym-var))
                   :class? cls?
                   :macro? macro?}
            (not macro?) (assoc :value (symbol sym-var))))))))

#_(let [specs              (remove #(= % '=>) fspec)
        current-ns         (if (enc/compiling-cljs?) (-> env** :ns :name name) (name (ns-name *ns*)))
        cljc-resolve       (fn [s]
                             (or
                               (if (enc/compiling-cljs?)
                                 (some-> (cljs.analyzer/resolve-var env** s) :name)
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
                   :meta           ~(meta form**)
                   ;; NOTE: we put speculative global resolution of all simple symbols
                   ;; in here. Then when we process `let` at runtime we'll know if there is a simple symbol
                   ;; in env and can prefer that, but we can use this to look up the resolved global name if necessary.
                   :global-symbols ~(deref symbol-map)
                   :checks         (fn [~'env] ~@body-checks)
                   :arity          {~arity {:argument-types ~(vec (butlast resolved-signature))
                                            :argument-list  (quote ~args)
                                            :return-type    ~(last resolved-signature)}}})))

(defn process-defn [env form args]
  (let [form-expression   (forms/form-expression form)
        sym               (second form)
        current-ns        (if (enc/compiling-cljs?) (-> env :ns :name name) (name (ns-name *ns*)))
        fqsym             `(symbol ~current-ns ~(name sym))
        expr              (symbol current-ns (name sym))
        extern-symbol-map (atom {})
        _                 (walk/postwalk (fn [f]
                                           (when (symbol? f)
                                             (when-let [extern (cljc-resolve env f)]
                                               (swap! extern-symbol-map assoc `(quote ~f) extern))))
                            ;; TODO: skip through the arglist
                            (rest (rest (rest form))))
        guard-type        (if (enc/compiling-cljs?)
                            :default
                            'Exception)]
    `(do
       (defn ~@args)
       (try
         (a/remember! ~fqsym ~{:name           fqsym
                               :value          expr
                               :extern-symbols @extern-symbol-map
                               :form           form-expression})
         (catch ~guard-type e#
           (log/error e# "Cannot record function info for GRP: " ~fqsym))))))

(defmacro >defn
  "Pro version of >defn. The non-pro version of this macro simply emits *this* macro if it is in pro mode."
  [& args]
  (process-defn &env &form args))

(comment
  (require 'cljs.analyzer.api)
  (meta (first (keys the-env)))
  (a/remember! 'x '()))
