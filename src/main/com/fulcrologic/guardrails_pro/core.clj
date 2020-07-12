(ns com.fulcrologic.guardrails-pro.core
  (:require
    [com.fulcrologic.guardrails-pro.static.forms :as forms]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [taoensso.encore :as enc]
    [clojure.walk :as walk]))

(try
  (require 'cljs.analyzer.api)
  (catch Exception _))

(defn cljc-resolve [env s]
  (if (enc/compiling-cljs?)
    (cljs.analyzer.api/resolve env s)
    (if (contains? env s)
      {:name s
       :op   :local}
      (when-let [sym (some->> s (ns-resolve *ns* env) (symbol))]
        {:name sym}))))

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
        global-symbol-map (atom {})
        _                 (walk/postwalk (fn [f]
                                           (when (symbol? f)
                                             (when-let [extern (cljc-resolve env f)]
                                               ;; TASK: make it so we emit code that is the ref to the real function as well, so we can call it.
                                               (swap! global-symbol-map assoc `(quote ~f) `(quote ~extern)))))
                            ;; TODO: skip through the arglist
                            (rest (rest (rest form))))]
    `(do
       (defn ~@args)
       (a/remember! ~fqsym ~{:name           fqsym
                             :global-symbols @global-symbol-map
                             :form           form-expression}))))

(defmacro >defn
  "Pro version of >defn. The non-pro version of this macro simply emits *this* macro if it is in pro mode."
  [& args]
  (process-defn &env &form args))

(comment
  (require 'cljs.analyzer.api)
  (meta (first (keys the-env)))
  (a/remember! 'x '()))
