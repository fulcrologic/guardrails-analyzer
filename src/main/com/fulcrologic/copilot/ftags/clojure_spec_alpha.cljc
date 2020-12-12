(ns com.fulcrologic.copilot.ftags.clojure-spec-alpha
  (:require
    clojure.test.check.generators
    [clojure.spec.alpha :as s]
    [com.fulcrologic.copilot.analysis.analyzer :refer [defanalyzer]]))

(s/def ::spec-key (s/or :kw qualified-keyword? :sym symbol?))

(s/def ::spec-val (s/or :kw qualified-keyword? :spec s/spec? :pred ifn? :regex-op s/regex?))

(defanalyzer clojure.spec.alpha/def [env expr] {})
