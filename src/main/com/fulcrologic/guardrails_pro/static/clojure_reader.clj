(ns com.fulcrologic.guardrails-pro.static.clojure-reader
  "A custom Clojure reader that puts source file location metadata on every form that can *have* metadata (like CLJS does)
   so that we can report information more accurately.

   Here's the idea: We cannot *fix* the Clojure reader itself. Even if we found a hack, it could lead to badness.

   So instead *this* reader is called from the `>defn` macro. Meaning:

   1. Clojure reads the file.
   2. Clojure does macro expansion.
   3. `>defn`, when being macroexpanded (run), calls *this* reader to re-read just the function (itself)
   4. We use that newly read form instead of `&form` in order to capture better source location info.
   "
  (:require
    [clojure.tools.reader :as reader]
    [clojure.tools.reader.impl.utils :as reader.utils]
    [clojure.java.io :as io]
    [taoensso.timbre :as log])
  (:import
    (java.io FileReader BufferedReader PushbackReader)
    (clojure.tools.reader.reader_types SourceLoggingPushbackReader)))

(defn read-ns-decl [file]
  (try
    (let [ns-decl (reader/read
                    (new PushbackReader
                      (new FileReader file)))]
      (assert (= 'ns (first ns-decl)))
      (second ns-decl))
    (catch Throwable _
      (log/debug "Failed to read ns decl from:" file)
      nil)))

(defn- file->reader [file]
  (new BufferedReader
    (if (.startsWith file "/")
      (new FileReader file)
      (-> file io/resource io/reader))))

;; NOTE: ? - with-redefs on symbol resolution?
;; NOTE: ? - missing a require?

(defn read-form
  "Read and return the form specified by the passed in var.
   The returned form will be augmented so that *everything* that
   *can* have metadata will include file/line/start column/ending column information."
  [file line]
  (let [r (file->reader file)]
    (doseq [_ (range (dec line))]
      (.readLine r))
    (reader/read
      (new SourceLoggingPushbackReader
        (new PushbackReader r)
        line 1 true nil 0 file
        (doto (reader.utils/make-var)
          (alter-var-root (constantly {:buffer (StringBuilder.) :offset 0})))
        false))))

(comment
  ((requiring-resolve 'com.fulcrologic.guardrails-pro.static.forms/form-expression)
   (read-form (meta #'read-form))))
