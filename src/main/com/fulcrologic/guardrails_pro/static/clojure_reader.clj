(ns com.fulcrologic.guardrails-pro.static.clojure-reader
  (:require
    [clojure.tools.reader :as reader]
    [clojure.tools.reader.impl.utils :as reader.utils]
    [clojure.java.io :as io]
    [com.rpl.specter :as sp]
    [taoensso.timbre :as log])
  (:import
    (java.io FileReader BufferedReader PushbackReader)
    (clojure.tools.reader.reader_types SourceLoggingPushbackReader)))

(defn read-ns-decl [file]
  (try
    (let [ns-decl (reader/read
                    {:read-cond :allow}
                    (new PushbackReader
                      (new FileReader file)))]
      (assert (= 'ns (first ns-decl)))
      (second ns-decl))
    (catch Throwable t
      (log/debug t "Failed to read ns decl from:" file)
      nil)))

(defn- file->reader [file]
  (new BufferedReader
    (if (.startsWith file "/")
      (new FileReader file)
      (-> file io/resource io/reader))))

(defn parse-ns-aliases [ns-form]
  (->> ns-form
    (sp/select [(sp/walker #(and (vector? %) (some #{:as} %)))])
    (map (fn [[ns-sym & args]]
           {(:as (apply hash-map args)) ns-sym}))
    (reduce merge)))

(defn read-form [file line]
  (let [r (file->reader file)]
    (doseq [_ (range (dec line))]
      (.readLine r))
    (reader/read {:read-cond :allow}
      (new SourceLoggingPushbackReader
        (new PushbackReader r)
        line 1 true nil 0 file
        (doto (reader.utils/make-var)
          (alter-var-root (constantly {:buffer (StringBuilder.) :offset 0})))
        false))))

(defn read-form-at
  "Read and return the form at `file` and `line`.
   The returned form will be augmented so that *everything* that
   *can* have metadata will include file/line/start column/ending column information."
  [file line]
  (let [ns-form (read-form file 0)]
    (binding [reader/*alias-map* (parse-ns-aliases ns-form)
              *ns* (second ns-form)]
      (read-form file line))))
