(ns com.fulcrologic.guardrails-analyzer.analysis.analyzer.hofs-spec
  (:require
   [com.fulcrologic.guardrails-analyzer.analysis.analyzer.hofs :as hofs]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(def sample-td
  "A type description with arities containing gspecs, as the analyzer produces."
  {::cp.art/arities
   {1 {::cp.art/gspec {::cp.art/argument-specs  [int?]
                       ::cp.art/argument-types  ["int?"]
                       ::cp.art/return-spec     string?
                       ::cp.art/return-type     "string?"
                       ::cp.art/return-predicates [string?]
                       ::cp.art/argument-predicates [int?]}}
    2 {::cp.art/gspec {::cp.art/argument-specs  [int? string?]
                       ::cp.art/argument-types  ["int?" "string?"]
                       ::cp.art/return-spec     boolean?
                       ::cp.art/return-type     "boolean?"
                       ::cp.art/return-predicates [boolean?]
                       ::cp.art/argument-predicates [int? string?]}}}})

(specification ">fn-ret"
               (let [result (hofs/>fn-ret sample-td)]
                 (assertions
                  "retains only return-related keys in each gspec"
                  (get-in result [::cp.art/arities 1 ::cp.art/gspec])
                  => {::cp.art/return-spec       string?
                      ::cp.art/return-type       "string?"
                      ::cp.art/return-predicates [string?]}

                  "strips argument keys from each arity's gspec"
                  (contains? (get-in result [::cp.art/arities 1 ::cp.art/gspec]) ::cp.art/argument-specs)
                  => false

                  "works across multiple arities"
                  (get-in result [::cp.art/arities 2 ::cp.art/gspec])
                  => {::cp.art/return-spec       boolean?
                      ::cp.art/return-type       "boolean?"
                      ::cp.art/return-predicates [boolean?]}))

               (assertions
                "handles empty arities"
                (hofs/>fn-ret {::cp.art/arities {}})
                => {::cp.art/arities {}}))

(specification ">fn-args"
               (let [result (hofs/>fn-args sample-td)]
                 (assertions
                  "retains only argument-related keys in each gspec"
                  (get-in result [::cp.art/arities 1 ::cp.art/gspec])
                  => {::cp.art/argument-specs      [int?]
                      ::cp.art/argument-types      ["int?"]
                      ::cp.art/argument-predicates [int?]}

                  "strips return keys from each arity's gspec"
                  (contains? (get-in result [::cp.art/arities 1 ::cp.art/gspec]) ::cp.art/return-spec)
                  => false

                  "works across multiple arities"
                  (get-in result [::cp.art/arities 2 ::cp.art/gspec])
                  => {::cp.art/argument-specs      [int? string?]
                      ::cp.art/argument-types      ["int?" "string?"]
                      ::cp.art/argument-predicates [int? string?]}))

               (assertions
                "handles empty arities"
                (hofs/>fn-args {::cp.art/arities {}})
                => {::cp.art/arities {}}))
