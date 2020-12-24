(ns com.fulcrologic.copilot.reader
  (:require
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.copilot.transit-handlers :as f.transit]
    [com.fulcrologicpro.clojure.tools.reader :as reader]
    [com.fulcrologicpro.clojure.tools.reader.reader-types :as readers]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologicpro.taoensso.encore :as enc])
  (:import
    (java.io PushbackReader File)))

(defn default-data-reader [tag value]
  (f.transit/->UnknownTaggedValue tag value))

(defn read-impl [& args]
  (binding [reader/*default-data-reader-fn* default-data-reader]
    (apply reader/read args)))

(defn parse:refers [lib [kind value]]
  (case kind
    :syms {:refers (into {}
                     (map (fn [sym] [sym (symbol (str lib) (str sym))])
                       value))}
    {}))

(defn parse:lib+opts [{:keys [lib options]}]
  (cond-> {}
    (:as options) (assoc :aliases {(:as options) lib})
    (:refer options) (merge (parse:refers lib (:refer options)))))

(defn parse:libspec [[kind value]]
  (case kind
    :lib+opts (parse:lib+opts value)
    nil))

(defn parse:require-clause [[kind value]]
  (case kind
    :libspec (parse:libspec value)
    :prefix-list (let [{:keys [prefix libspecs]} value]
                   (-> (->> libspecs
                         (map parse:libspec)
                         (into {}))
                     (update :aliases (partial enc/map-keys
                                        #(symbol (str prefix "." %))))
                     (update :refers (partial enc/map-vals
                                       #(symbol (str prefix "." %))))))
    nil))

(defn parse-ns [ns-form]
  (let [conformed-ns (s/conform :clojure.core.specs.alpha/ns-form
                       (cp.art/unwrap-meta (rest ns-form)))
        requires (:body (:require (into {} (:ns-clauses conformed-ns))))]
    (reduce enc/nested-merge {}
      (map parse:require-clause requires))))

(defn read-file [file reader-cond-branch]
  (let [eof       (new Object)
        reader    (readers/indexing-push-back-reader
                    (new PushbackReader
                      (io/reader file)))
        opts      {:eof       eof
                   :read-cond :allow
                   :features  #{reader-cond-branch}}
        ns-decl   (cp.art/unwrap-meta (read-impl opts reader))
        _         (assert (= 'ns (first ns-decl))
                    (format "First form in file <%s> was not a ns declaration!"
                      (if (instance? File file)
                        (.getAbsolutePath file)
                        "<input stream>")))
        NS        (create-ns (second ns-decl))
        parsed-ns (parse-ns ns-decl)
        forms     (loop [forms []]
                    (let [form (binding [*ns* NS, reader/*alias-map* (:aliases parsed-ns)]
                                 (read-impl opts reader))]
                      (if (identical? form eof)
                        (do (.close reader) forms)
                        (recur (conj forms form)))))]
    (merge parsed-ns {:file (str file) :NS (str NS) :forms forms})))
