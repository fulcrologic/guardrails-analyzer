(ns com.fulcrologicpro.clojure.tools.reader-spec
  (:require
    [com.fulcrologic.guardrails-analyzer.test-checkers :as tc]
    [com.fulcrologicpro.clojure.tools.reader :as reader]
    [com.fulcrologicpro.clojure.tools.reader.reader-types :as readers]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [assertions specification]]))

(defn test:read
  ([string] (test:read string {}))
  ([string opts]
   (->> string
     (readers/indexing-push-back-reader)
     (reader/read opts))))

(specification "literals are wrapped with location metadata"
  (assertions
    "nil"
    (test:read "nil")
    =check=> (_/embeds?*
               {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                :value                                 nil
                :metadata                              {:column 1 :end-column 4}})
    "boolean"
    (test:read "true")
    =check=> (_/embeds?*
               {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                :value                                 true
                :metadata                              {:column 1 :end-column 5}})
    (test:read "false")
    =check=> (_/embeds?*
               {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                :value                                 false
                :metadata                              {:column 1 :end-column 6}})
    "number"
    (test:read "123")
    =check=> (_/embeds?*
               {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                :value                                 123
                :metadata                              {:column 1 :end-column 3}})
    "char"
    ;; TODO: Not sure is right, but just "\c" throws an error
    (test:read "\\c")
    =check=> (_/embeds?*
               {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                :value                                 \c
                :metadata                              {:column 1 :end-column 3}})
    "string"
    (test:read "\"foo\"")
    =check=> (_/embeds?*
               {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                :value                                 "foo"
                :metadata                              {:column 1 :end-column 6}})
    "regex"
    (test:read "#\"foo\"")
    =check=> (_/embeds?*
               {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                :value                                 (tc/fmap* str (_/equals?* "foo"))
                :metadata                              {:column 2 :end-column 7}})
    "keyword"
    (test:read ":foo")
    =check=> (_/embeds?*
               {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                :value                                 :foo
                :metadata                              {:column 1 :end-column 5}})))

(specification "can still read"
  (assertions
    "reader conditionals"
    (test:read "#?(:clj 1 :cljs 2)"
      {:read-cond :allow
       :features  #{:clj}})
    =check=> (_/embeds?*
               {:com.fulcrologic.guardrails-analyzer/meta-wrapper? true
                :value                                 1})))
