(ns com.fulcrologic.guardrails-pro.core
  (:require
    [clojure.walk :as walk]
    [com.fulcrologic.guardrails-pro.parser :as grp.parser]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.clojure-reader :as clj-reader]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]
    [clojure.spec.alpha :as s]
    [clojure.string :as str])
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
  (grp.parser/parse-defn
    (rest
      (if (enc/compiling-cljs?)
        form
        (clj-reader/read-form
          ;; FIXME: might be broken (not tested yet)
          file (:line (meta form)))))))

(defn >defn-impl [env form [defn-sym :as args]]
  (try
    (let [current-ns (if (enc/compiling-cljs?)
                       (-> env :ns :name name)
                       (name (ns-name *ns*)))
          now        (grp.art/now-ms)
          fqsym      `(symbol ~current-ns ~(name defn-sym))
          fn-ref     (symbol current-ns (name defn-sym))]
      (let [{::grp.art/keys [arities location]} (parse-defn form *file*)
            extern-symbols (record-extern-symbols env arities)]
        `(do
           (defn ~@args)
           (try
             (grp.art/register-function! ~fqsym
               ~{::grp.art/name           fqsym
                 ::grp.art/fn-ref         fn-ref
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
  (let [arities    (grp.parser/parse-fdef args)
        resolution (cljc-resolve env sym)]
    (if resolution
      `(grp.art/register-external-function! '~sym
         #::grp.art{:name         '~sym
                    :fn-ref       ~sym
                    :arities      ~arities
                    :last-changed ~(inst-ms (Date.))})
      (do
        (log/warn ">ftag failed to resolve: " sym)
        nil))))

(defmacro >ftag
  "Tag an existing function that has a spec with a guardrails-pro spec."
  [& args]
  (>ftag-impl &env args))

(defn >fn-impl [env args]
  (let [fn> (grp.parser/parse-fn args)]
    `(do nil)))

(comment
  (>defn g []
    (let [s (map (>fn [x] [int? => string?]) [1 2 3])]
      s))

  `(do
     (defn g []
       (let [a 23
             s (map (>fn *gennm [x] [int? => string?]
                      (str/includes? a x)) [1 2 3])]
         s))
     (register! `g
       {::arities       {1 {::gspec ...}}
        :extern-symbols {'int?                         clojure.core/int?
                         's/keys                       clojure.spec.alpha/keys
                         '(s/keys :req [:person/name]) (s/keys :req [:person/name])}
        :lambdas        {'*gennm {::arities {...}
                                  :env->fn  #(let [a (from-env 'a %)]
                                               (fn [x] ^:pure [int? => string?]
                                                 (str/includes? a x)))}}
        ;; metadata on the >fn form says its name
        :body           '(let [s (map (>fn *gennm [x] [(s/keys :req [:person/name])
                                                       => string?]) [1 2 3])]
                           s)})))

(defmacro >fn [& args]
  (>fn-impl &env args))

(defn >fspec-impl [env args]
  (let [fspec> (grp.parser/parse-fspec args)]
    `(do nil)))

(defmacro >fspec [& args]
  (>fspec-impl &env args))
