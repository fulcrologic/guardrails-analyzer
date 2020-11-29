(ns com.fulcrologic.copilot.transit-handlers
  (:require
    [com.fulcrologicpro.cognitect.transit :as transit]
    [com.fulcrologicpro.fulcro.algorithms.transit :as f.transit]
    [com.fulcrologicpro.taoensso.timbre :as log])
  (:import
    com.fulcrologicpro.com.cognitect.transit.DefaultReadHandler
    java.util.regex.Pattern))

(deftype UnknownTaggedValue [tag value])

(defonce _
  (do
    (log/info "Installing custom type handlers")
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
