(ns com.fulcrologic.guardrails-pro.core
  (:require
    [com.fulcrologic.guardrails-pro.static.parser :as grp.parser]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.clojure-reader :as clj-reader]
    [com.rpl.specter :as sp]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

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

(defn record-extern-symbols [env args]
  (into {}
    (keep (fn [sym]
            (when-let [extern (cljc-resolve env sym)]
              (vector `(quote ~sym) extern))))
    (sp/select [(sp/codewalker symbol?)] args)))

(defn parse-defn [form file extern-symbols]
  (grp.parser/parse-defn
    (rest
      (if (enc/compiling-cljs?)
        form
        (clj-reader/read-form
          ;; FIXME: might be broken (not tested yet)
          file (:line (meta form)))))
    extern-symbols))

(defn >defn-impl [env form [defn-sym :as args]]
  (try
    (let [current-ns (if (enc/compiling-cljs?)
                       (-> env :ns :name name)
                       (name (ns-name *ns*)))
          now        (grp.art/now-ms)
          fqsym      `(symbol ~current-ns ~(name defn-sym))
          fn-ref     (symbol current-ns (name defn-sym))]
      (let [extern-symbols (record-extern-symbols env args)
            {::grp.art/keys [arities location lambdas]} (parse-defn form *file* (map second (keys extern-symbols)))
            ]
        `(do
           (defn ~@args)
           (try
             (grp.art/register-function! ~fqsym
               ~{::grp.art/name           fqsym
                 ::grp.art/fn-ref         fn-ref
                 ::grp.art/lambdas        lambdas
                 ::grp.art/arities        arities
                 ::grp.art/location       location
                 ::grp.art/last-changed   now
                 ::grp.art/extern-symbols extern-symbols})
             (catch ~(if (enc/compiling-cljs?) :default 'Exception) e#
               (log/error e# "Cannot record function info for GRP:" ~fqsym)))
           (var ~fn-ref))))
    (catch Throwable t
      (log/error "Failed to do analysis on" (first args) ":" (ex-message t))
      `(defn ~@args))))

(defmacro >defn
  "Pro version of >defn. The non-pro version of this macro simply emits *this* macro if it is in pro mode."
  [& args]
  (>defn-impl &env &form args))

;;TODO: guardrails >fdef should emit call to >ftag-impl if pro?
(defn >ftag-impl [env [sym :as args]]
  (let [{::grp.art/keys [arities]} (grp.parser/parse-fdef args)
        resolution (cljc-resolve env sym)]
    (if resolution
      `(grp.art/register-external-function! '~sym
         #::grp.art{:name         '~sym
                    :fn-ref       ~sym
                    :arities      ~arities
                    :last-changed ~(grp.art/now-ms)})
      (do
        (log/warn ">ftag failed to resolve: " sym)
        nil))))

(defmacro >ftag
  "Tag an existing function that has a spec with a guardrails-pro spec."
  [& args]
  (>ftag-impl &env args))

(defmacro >fn [& args]
  `(fn ~@args))

(defn >fspec-impl [env args]
  (let [fspec (grp.parser/parse-fspec args)]
    `(do ~fspec)))

(defmacro >fspec [& args]
  (>fspec-impl &env args))
