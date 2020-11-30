(ns tools-analyzer
  (:require
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.copilot.daemon.reader :as drdr]
    [clojure.tools.reader.reader-types :as readers]
    [clojure.tools.analyzer :as ana]
    [clojure.tools.analyzer.env :as env])
  (:import (java.io StringReader)))

(defn analyze [form env]
  (binding [ana/macroexpand-1 (fn [form & env] form)
            ana/create-var    identity
            ana/parse         ana/-parse
            ana/var?          var?]
    (env/ensure env
      (ana/analyze form env))))

(comment
  (let [content "(ns foo
  (:require [bah :as b]))
 (defn a [x] (b/f x))"
        eof     (new Object)
        reader  (readers/indexing-push-back-reader
                  (java.io.PushbackReader. (StringReader. content)))
        opts    {:eof       eof
                 :read-cond :allow
                 :features  #{:clj}}]
    (loop [form (drdr/read-impl opts reader)]
      (when-not (= eof form)
        (println form)
        ;; Must do cumulative processing of analysis, or it cannot proceed.
        ;; ana/analyze if a multimethod. see line 234 of c.t.analyzer for symbol analysis. It's pluggable, just
        ;; need to read dispatch to get what we want I think. Macroexpand also seems to be something we can
        ;; mess with.
        (println (analyze form {:ns         'foo
                        :namespaces {'foo {:aliases {'b 'bah}}
                                     'b   {:ns 'bah}
                                     'bah {:ns 'bah}}}))
        (recur (drdr/read-impl opts reader)))))

  ;; SUCKS down the memory to an OOM, slow as heck. Totally non-option
  (ana.jvm/analyze-ns 'com.fulcrologicpro.fulcro.ui-state-machines)

  )
