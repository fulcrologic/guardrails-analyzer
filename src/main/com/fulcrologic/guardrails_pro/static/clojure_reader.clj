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
    [clojure.tools.reader.impl.utils :as reader.utils])
  (:import
    (java.io FileReader BufferedReader PushbackReader)
    (clojure.tools.reader.reader_types SourceLoggingPushbackReader)))

(defn read-form
  "Read and return the form specified by the passed in var.
   The returned form will be augmented so that *everything* that
   *can* have metadata will include file/line/start column/ending column information."
  [form-var]
  (let [{:keys [file line]} (meta form-var)
        r (new BufferedReader
            (new FileReader file))
        pbr (new PushbackReader r)]
    (doseq [_ (range (dec line))]
      (.readLine r))
    (reader/read
      (new SourceLoggingPushbackReader
        pbr line 1 true nil 0 file
        (doto (reader.utils/make-var)
          (alter-var-root (constantly {:buffer (StringBuilder.) :offset 0})))
        false))))

(comment
  ((requiring-resolve 'com.fulcrologic.guardrails-pro.static.forms/form-expression)
   (read-form #'read-form)))
