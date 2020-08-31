(ns com.fulcrologic.guardrails-pro.core
  (:require
    [clojure.java.io :as io]
    [clojure.walk :as walk]
    [com.fulcrologic.guardrails-pro.parser :as parser]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.clojure-reader :as clj-reader]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log])
  (:import
    (java.util Date)))


(try
  (require 'cljs.analyzer.api)
  (catch Exception _))

(defn cljc-resolve [env s]
  (if (enc/compiling-cljs?)
    (let [ast-node (cljs.analyzer.api/resolve env s)
          macro?   (boolean (:macro ast-node))]
      (when ast-node
        (cond-> {::grp.art/extern-name `(quote ~(:name ast-node))
                 :op                   (:op ast-node)
                 ::grp.art/macro?      macro?}
          macro? (assoc :op :macro)
          (not macro?) (assoc ::grp.art/value s))))
    (if (contains? env s)
      {::grp.art/extern-name `(quote ~s)
       ::grp.art/macro?      false
       :op                   :local
       ::grp.art/value       s}
      (let [sym-var (ns-resolve *ns* env s)
            cls?    (class? sym-var)
            macro?  (boolean (and (not cls?) (some-> sym-var meta :macro)))]
        (when sym-var
          (cond-> {::grp.art/extern-name `(quote ~(symbol sym-var))
                   ::grp.art/class?      cls?
                   ::grp.art/macro?      macro?}
            (not macro?) (assoc ::grp.art/value (symbol sym-var))))))))

(defn record-extern-symbols [env arities]
  (let [extern-symbol-map (atom {})]
    (walk/postwalk (fn [f]
                     (when (symbol? f)
                       (when-let [extern (cljc-resolve env f)]
                         (swap! extern-symbol-map assoc `(quote ~f) extern))))
      (vec
        (->> (vals arities)
          (filter #(and (map? %) (contains? (meta %) ::grp.art/raw-body)))
          (mapcat #(-> % meta ::grp.art/raw-body)))))
    @extern-symbol-map))

(defn parse-defn [form file]
  (parser/parse-defn-args
    (rest
      (if (enc/compiling-cljs?)
        form
        (clj-reader/read-form
          ;; FIXME: might be broken (not tested yet)
          file (:line (meta form)))))))

(defn process-defn [env form [defn-sym :as args]]
  (try
    (let [current-ns     (if (enc/compiling-cljs?)
                           (-> env :ns :name name)
                           (name (ns-name *ns*)))
          fqsym          `(symbol ~current-ns ~(name defn-sym))
          fn-ref         (symbol current-ns (name defn-sym))
          {::grp.art/keys [arities location]} (parse-defn form *file*)
          extern-symbols (record-extern-symbols env arities)]
      `(do
         (defn ~@args)
         (try
           (grp.art/register-function! ~fqsym
             ~{::grp.art/name           fqsym
               ::grp.art/fn-ref         fn-ref
               ::grp.art/arities        arities
               ::grp.art/location       location
               ::grp.art/last-changed   (inst-ms (Date.))
               ::grp.art/extern-symbols extern-symbols})
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
  (>defn env-test
    [x]
    [int? :ret int?]
    "a string")
  )
