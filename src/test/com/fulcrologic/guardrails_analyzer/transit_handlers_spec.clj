(ns com.fulcrologic.guardrails-analyzer.transit-handlers-spec
  "Behavioral tests for the transit handlers — particularly the RCE regression
   that arose from using `clojure.core/read-string` (which honors `*read-eval*`
   and would happily evaluate `#=(...)` reader-eval forms baked into a transit
   payload). The handlers MUST use `clojure.edn/read-string` so that an inbound
   payload can never trigger arbitrary code execution.

   Behaviors covered here:
     * default-read-handler returns inert EDN data without evaluating it
     * default-read-handler rejects `#=(...)` reader-eval forms (no eval)
     * guardrails/unknown-tag handler rejects malicious transit payloads
       containing `#=(...)` content (no eval)
     * read-edn round-trips a safe EDN tagged literal (#inst) via the
       guardrails/unknown-tag pathway, proving the safe edn reader is in use"
  (:require
   [com.fulcrologic.guardrails-analyzer.test-fixtures :as tf]
   [com.fulcrologic.guardrails-analyzer.transit-handlers :as sut]
   [fulcro-spec.core :refer [=> =throws=> assertions component specification]])
  (:import
   (java.util Date)))

(tf/use-fixtures :once tf/with-default-test-logging-config)

;; Touching the `defonce` ensures the type-handler installation has run before
;; the assertions execute. (Requiring `sut` already loads the namespace, but
;; this makes the dependency explicit and survives any future reload tooling.)
(deref #'sut/_)

(defn- safe-call
  "Invoke `f`, returning either `[:ok value]` or `[:threw <ex-class-name>]`.
   Used so an exception during a side-effect probe can't take the test JVM
   down even if the regression were re-introduced."
  [f]
  (try [:ok (f)]
       (catch Throwable t [:threw (.getName (class t))])))

(specification "default-read-handler — uses clojure.edn (no read-eval)"
               (component "returns inert EDN for plain content"
                          (assertions
                           "a callable-looking list is returned as inert data, not evaluated"
                           (.fromRep sut/default-read-handler "any-tag" "(System/exit 0)")
                           => '(System/exit 0)
                           "a qualified symbol is parsed as a symbol"
                           (.fromRep sut/default-read-handler "any-tag" "foo/bar")
                           => 'foo/bar
                           "a keyword literal is parsed as a keyword"
                           (.fromRep sut/default-read-handler "any-tag" ":hello/world")
                           => :hello/world))
               (component "rejects `#=` reader-eval forms (the RCE regression)"
                          (assertions
                           "throws on a #=(...) form rather than evaluating it"
                           (.fromRep sut/default-read-handler "any-tag" "#=(do :ignored)")
                           =throws=> Throwable))
               (component "the `#=` form does NOT execute as a side-effect"
                          ;; This is the proof-of-safety check. Under the prior
                          ;; (unsafe) implementation, the reader-eval form would
                          ;; have interned the var. We confirm it did not.
                          (let [result (safe-call
                                        #(.fromRep sut/default-read-handler "any-tag"
                                                   "#=(intern *ns* '__transit_pwned_default__ :pwned)"))]
                            (assertions
                             "the call threw rather than completing"
                             (first result) => :threw
                             "no var was interned via reader-eval (no eval happened)"
                             (resolve '__transit_pwned_default__) => nil))))

(specification "guardrails/unknown-tag handler — uses clojure.edn (no read-eval)"
               (component "round-trips a safe EDN tagged literal via read-edn/write-edn"
                          ;; The unknown-tag handler reconstructs the original
                          ;; EDN tagged literal string `#<tag> <value>` and reads
                          ;; it back via clojure.edn/read-string. EDN ships with
                          ;; a reader for `#inst`, so an instant survives the trip.
                          (let [original  (sut/->UnknownTaggedValue "inst" "\"2025-01-15T00:00:00.000-00:00\"")
                                wire      (sut/write-edn original)
                                roundtrip (sut/read-edn wire)]
                            (assertions
                             "the wire string is non-empty transit text"
                             (string? wire) => true
                             "the value reconstructed via #inst comes back as a Date instance"
                             (instance? Date roundtrip) => true
                             "the reconstructed instant has the expected millis"
                             (.getTime ^Date roundtrip)
                             => (.getTime #inst "2025-01-15T00:00:00.000-00:00"))))
               (component "rejects malicious transit payloads that would invoke read-eval (regression)"
                          ;; Wire format equivalent to:
                          ;;   (write-edn (->UnknownTaggedValue "=" "(intern ...)"))
                          ;; Under clojure.core/read-string this would build the
                          ;; string "#= (intern ...)" and EVALUATE it — granting
                          ;; arbitrary code execution to anything that can send a
                          ;; transit string to the daemon. Under clojure.edn this
                          ;; must throw.
                          (let [malicious "[\"~#guardrails/unknown-tag\",[\"=\",\"(intern *ns* '__transit_pwned_unknown__ :pwned)\"]]"
                                result    (safe-call #(sut/read-edn malicious))]
                            (assertions
                             "read-edn throws rather than evaluating the embedded #= form"
                             (first result) => :threw
                             "no var was interned via reader-eval (no eval happened)"
                             (resolve '__transit_pwned_unknown__) => nil))))
