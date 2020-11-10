(ns com.fulcrologic.guardrails-pro.test-checkers
  #?(:cljs (:require-macros [com.fulcrologic.guardrails-pro.test-checkers]))
  (:require
    #?@(:cljs [[goog.string.format]
               [goog.string :refer [format]]])
    #?@(:clj [[com.fulcrologic.guardrails.impl.parser :as impl.parser]])
    [clojure.set :as set]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :refer [analyze-mm]]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.literals :as grp.ana.lit]
    [fulcro-spec.check :as _ :refer [checker]]))

#?(:clj
   (defmacro >test-fn [& args]
     (let [parsed-fn (grp.art/fix-kw-nss (impl.parser/parse-fn args []))
           lambda (merge
                    {::grp.art/lambda-name `(quote ~(gensym ">test-fn$"))}
                    parsed-fn
                    {::grp.art/fn-ref `(fn ~@args)
                     ::test-fn? true})]
       `(grp.art/resolve-quoted-specs
          ~(::grp.art/spec-registry lambda)
          ~lambda))))

(defmethod analyze-mm :collection/map [env hashmap]
  (if ((every-pred ::test-fn? ::grp.art/arities) hashmap) hashmap
    (grp.ana.lit/analyze-hashmap! env hashmap)))

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
  [f c] {:pre [(fn? f) (_/checker? c)]}
  (_/checker [actual]
    (c (f actual))))
