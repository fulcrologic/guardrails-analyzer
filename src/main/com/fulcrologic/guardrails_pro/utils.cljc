(ns com.fulcrologic.guardrails-pro.utils
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [taoensso.timbre :as log]))

(>defn try-sampling [{::a/keys [return-spec generator]}]
  [::a/spec => (? (s/coll-of any? :min-count 1))]
  (try
    (gen/sample
      (or generator (s/gen return-spec)))
    (catch #?(:clj Exception :cljs :default) _
      (log/info "Cannot sample from:" (or generator return-spec))
      nil)))
