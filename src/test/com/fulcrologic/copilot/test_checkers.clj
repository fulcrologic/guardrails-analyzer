(ns com.fulcrologic.copilot.test-checkers
  (:require
   [clojure.set :as set]
   [com.fulcrologic.copilot.analysis.analyzer.dispatch :refer [analyze-mm]]
   [com.fulcrologic.copilot.analysis.analyzer.literals :as cp.ana.lit]
   [com.fulcrologic.copilot.artifacts :as cp.art]
   [com.fulcrologic.guardrails.impl.parser :as impl.parser]
   [fulcro-spec.check :as _ :refer [checker]]))

(defmacro >test-fn [& args]
  (let [parsed-fn (cp.art/fix-kw-nss (impl.parser/parse-fn args []))
        lambda (merge
                {::cp.art/lambda-name `(quote ~(gensym ">test-fn$"))}
                parsed-fn
                {::cp.art/fn-ref `(fn ~@args)
                 ::test-fn? true})]
    `(cp.art/resolve-quoted-specs
      ~(::cp.art/spec-registry lambda)
      ~lambda)))

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

(defn samples-satisfy?*
  "Checks that all samples in a collection satisfy the given predicate.
  Works with both regular samples and path-based samples."
  [pred]
  {:pre [(ifn? pred)]}
  (checker [actual]
           (let [samples (if (contains? actual ::cp.art/execution-paths)
                    ;; Path-based: extract all samples from all paths
                           (mapcat ::cp.art/samples (::cp.art/execution-paths actual))
                    ;; Regular: use samples directly
                           (::cp.art/samples actual))
                 failing (remove pred samples)]
             (when (seq failing)
               {:actual {:failing-samples (vec (take 5 failing))}
                :expected `(~'samples-satisfy?* ~pred)
                :message (format "Some samples don't satisfy predicate: %s" (vec (take 5 failing)))}))))

(defn samples-exist?*
  "Checks that samples exist and are non-empty."
  []
  (checker [actual]
           (let [samples (if (contains? actual ::cp.art/execution-paths)
                           (mapcat ::cp.art/samples (::cp.art/execution-paths actual))
                           (::cp.art/samples actual))]
             (when (empty? samples)
               {:actual actual
                :expected `(~'samples-exist?*)
                :message "Expected non-empty samples"}))))

(defn samples-count?*
  "Checks that the number of samples matches expectations."
  ([exact-count]
   (checker [actual]
            (let [samples (if (contains? actual ::cp.art/execution-paths)
                            (mapcat ::cp.art/samples (::cp.art/execution-paths actual))
                            (::cp.art/samples actual))
                  count (count samples)]
              (when-not (= exact-count count)
                {:actual {:count count}
                 :expected `(~'samples-count?* ~exact-count)
                 :message (format "Expected %d samples, got %d" exact-count count)}))))
  ([min-count max-count]
   (checker [actual]
            (let [samples (if (contains? actual ::cp.art/execution-paths)
                            (mapcat ::cp.art/samples (::cp.art/execution-paths actual))
                            (::cp.art/samples actual))
                  count (count samples)]
              (when-not (<= min-count count max-count)
                {:actual {:count count}
                 :expected `(~'samples-count?* ~min-count ~max-count)
                 :message (format "Expected sample count in [%d,%d], got %d" min-count max-count count)})))))
