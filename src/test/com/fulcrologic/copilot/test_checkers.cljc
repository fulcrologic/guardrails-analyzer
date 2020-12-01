(ns com.fulcrologic.copilot.test-checkers
  #?(:cljs (:require-macros [com.fulcrologic.copilot.test-checkers]))
  (:require
    #?@(:cljs [[goog.string.format]
               [goog.string :refer [format]]])
    #?@(:clj [[com.fulcrologic.guardrails.impl.parser :as impl.parser]])
    [clojure.set :as set]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :refer [analyze-mm]]
    [com.fulcrologic.copilot.analysis.analyzer.literals :as cp.ana.lit]
    [fulcro-spec.check :as _ :refer [checker]]))

#?(:clj
   (defmacro >test-fn [& args]
     (let [parsed-fn (cp.art/fix-kw-nss (impl.parser/parse-fn args []))
           lambda (merge
                    {::cp.art/lambda-name `(quote ~(gensym ">test-fn$"))}
                    parsed-fn
                    {::cp.art/fn-ref `(fn ~@args)
                     ::test-fn? true})]
       `(cp.art/resolve-quoted-specs
          ~(::cp.art/spec-registry lambda)
          ~lambda))))

(defmethod analyze-mm :collection/map [env hashmap]
  (if ((every-pred ::test-fn? ::cp.art/arities) hashmap) hashmap
    (cp.ana.lit/analyze-hashmap! env hashmap)))

(defn of-length?*
  ([exp-len]
   (checker [actual]
     (let [length (count actual)]
       (when-not (= exp-len length)
         {:actual actual
          :expected `(~'of-length?* :eq ~exp-len)
          :message (format "Expected count to be %d was %d" exp-len length)}))))
  ([min-len max-len]
   (checker [actual]
     (let [length (count actual)]
       (when-not (<= min-len length max-len)
         {:actual actual
          :expected `(~'of-length?* :min ~min-len :max ~max-len)
          :message (format "Expected count to be [%d,%d] was %d" min-len max-len length)})))))

(defn subset?* [expected]
  {:pre [(set? expected)]}
  (_/all*
    (_/is?* seqable?)
    (checker [actual]
      (when-not (set/subset? (set actual) expected)
        {:actual {:extra-values (set/difference (set actual) expected)}
         :expected `(~'subset?* ~expected)
         :message "Found extra values in set"}))))

(defn fmap*
  "NOTE: should be removed once in a fulcro-spec release"
  [f c] {:pre [(ifn? f) (_/checker? c)]}
  (_/checker [actual]
    (c (f actual))))
