(ns annotate
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:import (java.io File)))

(defn source-files []
  (let [main         (File. "src/main/com/fulcrologic")
        source-files (into []
                       (filter
                         (fn [^File f]
                           (let [nm (.getName f)]
                             (or
                               (str/ends-with? nm ".clj")
                               (str/ends-with? nm ".cljc")
                               (str/ends-with? nm ".cljs")))))
                       (file-seq main))]
    source-files))

(defn get-file [^File f]
  (let [content (slurp (io/reader f))
        lines   (str/split-lines content)]
    lines))

(defn annotate-file
  "Remove the old heading from the top of the given file, and add
   the given one.  This will remove all leading comments in the file and
   replace them with `heading` (which is a sequence of strings to be used as lines)."
  [^File f heading]
  (let [lines  (drop-while #(let [l (str/trim %)]
                              (or
                                (empty? (seq l))
                                (str/starts-with? l ";"))) (get-file f))
        output (str
                 (str/join "\n" (concat heading [""] lines))
                 "\n")]
    (.delete f)
    (spit f output)))

(defn add-license
  "Adds license notice to the top of all source files that get distributed
   to users. Uses docs/SOURCE-NOTICE.txt as the source. Must be run from
   project root. Only affects files in src/main/com/fulcrologic."
  []
  (let [files   (source-files)
        license (get-file (File. "docs/SOURCE-NOTICE.txt"))]
    (doseq [f files]
      (annotate-file f license))))

(comment
  (add-license))