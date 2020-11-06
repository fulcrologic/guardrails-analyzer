(ns com.fulcrologic.guardrails-pro.transit-handlers
  (:require
    [cognitect.transit :as transit]
    [com.fulcrologic.fulcro.algorithms.transit :as f.transit])
  (:import
    com.cognitect.transit.DefaultReadHandler
    java.util.regex.Pattern))

(deftype UnknownTaggedValue [tag value])

(defonce _
  (do
    (f.transit/install-type-handler!
      (f.transit/type-handler
        Pattern "guardrails/regex"
        str re-pattern))
    ;; NOTE: for user unknown tagged values
    ;;  - but should not fail in grp code
    (f.transit/install-type-handler!
      (f.transit/type-handler
        UnknownTaggedValue "guardrails/unknown-tag"
        #(vector (.tag %) (.value %))
        (fn [[tag value]]
          (read-string
            (str "#" tag " " value)))))))

;; NOTE: for user specific tags with no transit handlers
(def default-write-handler
  (let [tag "guardrails/default-handler"]
    (transit/write-handler
      (fn [_] tag)
      (fn [x] (pr-str x)))))

(def default-read-handler
  (reify DefaultReadHandler
    (fromRep [this tag rep]
      (read-string rep))))

(def read-edn #(f.transit/transit-str->clj % {:default-handler default-read-handler}))
(def write-edn f.transit/transit-clj->str)
