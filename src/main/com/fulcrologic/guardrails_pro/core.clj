(ns com.fulcrologic.guardrails-pro.core
  (:require
    [clojure.java.io :as io]
    [clojure.walk :as walk]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails-pro.static.forms :as forms]
    [com.fulcrologic.guardrails-pro.static.clojure-reader :as clj-reader]
    [com.fulcrologic.guardrails-pro.parser :as parser]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log])
  (:import
    (java.io File)
    (java.util Date)))

(try
  (require 'cljs.analyzer.api)
  (catch Exception _))

(defn cljc-resolve [env s]
  (if (enc/compiling-cljs?)
    (let [ast-node (cljs.analyzer.api/resolve env s)
          macro?   (boolean (:macro ast-node))]
      (when ast-node
        (cond-> {::a/extern-name `(quote ~(:name ast-node))
                 :op             (:op ast-node)
                 ::a/macro?      macro?}
          macro? (assoc :op :macro)
          (not macro?) (assoc ::a/value s))))
    (if (contains? env s)
      {::a/extern-name `(quote ~s)
       ::a/macro?      false
       :op             :local
       ::a/value       s}
      (let [sym-var (ns-resolve *ns* env s)
            cls?    (class? sym-var)
            macro?  (boolean (and (not cls?) (some-> sym-var meta :macro)))]
        (when sym-var
          (cond-> {::a/extern-name `(quote ~(symbol sym-var))
                   ::a/class?      cls?
                   ::a/macro?      macro?}
            (not macro?) (assoc ::a/value (symbol sym-var))))))))

(defn record-body-forms! [env arities]
  (let [extern-symbol-map (atom {})]
    (walk/postwalk (fn [f]
                     (when (symbol? f)
                       (when-let [extern (cljc-resolve env f)]
                         (swap! extern-symbol-map assoc `(quote ~f) extern))))
      (vec
        (->> (vals arities)
          (filter #(and (map? %) (contains? (meta %) ::a/raw-body)))
          (mapcat #(-> % meta ::a/raw-body)))))
    @extern-symbol-map))

(defn process-defn [env form [defn-sym :as args]]
  (try
    (let [current-ns        (if (enc/compiling-cljs?)
                              (-> env :ns :name name)
                              (name (ns-name *ns*)))
          arities           (parser/parse-defn-args
                              (rest (clj-reader/read-form
                                      (merge (meta form)
                                        (if (.startsWith *file* "/")
                                          {:file *file*}
                                          {:resource (io/resource *file*)})))))
          fqsym             `(symbol ~current-ns ~(name defn-sym))
          fn-ref            (symbol current-ns (name defn-sym))
          extern-symbol-map (record-body-forms! env arities)]
      `(do
         (defn ~@args)
         (try
           (a/remember! ~fqsym ~{::a/name           fqsym
                                 ::a/last-changed   (inst-ms (Date.))
                                 ::a/fn-ref         fn-ref
                                 ::a/arities        arities
                                 ::a/extern-symbols extern-symbol-map})
           (catch ~(if (enc/compiling-cljs?) :default 'Exception) e#
             (log/error e# "Cannot record function info for GRP:" ~fqsym)))
         (var ~fn-ref)))
    (catch Throwable t
      (log/error "Failed to do analysis on" (first args) ":" (ex-message t))
      `(defn ~@args))))

(defmacro >defn
  "Pro version of >defn. The non-pro version of this macro simply emits *this* macro if it is in pro mode."
  [& args]
  (process-defn &env &form args))

(comment
  (process-defn {} nil
    '(foobar [x] [int? => int?] (let [y (inc x)] (+ x y))))

  (>defn env-test [x] [int? :ret int?] (inc x))
  )
