(ns com.fulcrologic.copilot.daemon.reader
  (:require
    [com.fulcrologicpro.clojure.tools.reader :as reader]
    [com.fulcrologicpro.clojure.tools.reader.reader-types :as readers]
    [com.fulcrologic.copilot.transit-handlers :as f.transit]
    [com.fulcrologicpro.com.rpl.specter :as $]
    [com.fulcrologicpro.taoensso.timbre :as log]
    [clojure.java.io :as io])
  (:import
    (java.io FileReader PushbackReader File)))

(defn default-data-reader [tag value]
  (f.transit/->UnknownTaggedValue tag value))

(defn read-impl [& args]
  (binding [reader/*default-data-reader-fn* default-data-reader]
    (apply reader/read args)))

(defn read-ns-decl [file]
  (try
    (let [ns-decl (read-impl
                    {:read-cond :allow}
                    (new PushbackReader
                      (new FileReader file)))]
      (assert (= 'ns (first ns-decl)))
      (second ns-decl))
    (catch Throwable t
      (log/debug t "Failed to read ns decl from:" file)
      nil)))

(defn parse-ns-aliases [ns-form]
  (->> ns-form
    ($/select [($/walker #(and (vector? %) (some #{:as} %)))])
    (map (fn [[ns-sym & args]]
           {(:as (apply hash-map args)) ns-sym}))
   (reduce merge)))

(defn read-file [file reader-cond-branch]
 (let [eof     (new Object)
        reader  (readers/indexing-push-back-reader
                  (new PushbackReader
                    (io/reader file)))
        opts    {:eof       eof
                 :read-cond :allow
                 :features  #{reader-cond-branch}}
        ns-decl (binding [reader/*wrap-meta?* false]
                  (read-impl opts reader))
        _       (assert (= 'ns (first ns-decl))
                  (format "First form in file <%s> was not a ns declaration!"
                    (if (instance? File file)
                      (.getAbsolutePath file)
                      "<input stream>")))
        NS      (create-ns (second ns-decl))
        aliases (parse-ns-aliases ns-decl)
        forms   (loop [forms []]
                  (let [form (binding [reader/*alias-map* aliases, *ns* NS]
                               (read-impl opts reader))]
                    (if (identical? form eof)
                      (do (.close reader) forms)
                      (recur (conj forms form)))))]
    {:NS (str NS) :ns-decl ns-decl :forms forms}))
