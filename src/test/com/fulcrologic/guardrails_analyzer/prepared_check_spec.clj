(ns com.fulcrologic.guardrails-analyzer.prepared-check-spec
  (:require
   [com.fulcrologic.guardrails-analyzer.prepared-check :as sut]
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [fulcro-spec.core :refer [assertions specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(specification "prepared-check var"
               (assertions
                "is interned in the prepared-check namespace"
                (some? (ns-resolve 'com.fulcrologic.guardrails-analyzer.prepared-check
                                   'prepared-check))
                => true

                "holds a value that supports deref"
                (instance? clojure.lang.IDeref sut/prepared-check)
                => true

                "holds an atom (supports reset!/swap! via IAtom)"
                (instance? clojure.lang.IAtom sut/prepared-check)
                => true))

(specification "prepared-check namespace metadata"
               (assertions
                "is marked :clj-reload/no-reload so reloads do not clobber the cached check"
                (-> (find-ns 'com.fulcrologic.guardrails-analyzer.prepared-check)
                    meta
                    :clj-reload/no-reload)
                => true))

(specification "prepared-check atom semantics"
               ;; Rebind the var to an isolated atom so that observing/mutating values here
               ;; cannot leak between tests or interfere with the production-shared atom.
               (with-redefs [sut/prepared-check (atom nil)]
                 (assertions
                  "starts as nil when freshly initialized"
                  @sut/prepared-check
                  => nil

                  "reset! replaces the held value"
                  (do (reset! sut/prepared-check {:checker :example})
                      @sut/prepared-check)
                  => {:checker :example}

                  "swap! transforms the held value"
                  (do (reset! sut/prepared-check 1)
                      (swap! sut/prepared-check inc)
                      @sut/prepared-check)
                  => 2)))
