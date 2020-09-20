(ns com.fulcrologic.guardrails-pro.static.clojure-reader-spec
  (:require
    [com.fulcrologic.guardrails-pro.static.clojure-reader :as clj-reader]
    [fulcro-spec.core :refer [specification assertions]]))

(specification "parse-ns-aliases"
  (assertions
    (clj-reader/parse-ns-aliases
      '(ns fulcro-spec.core
         (:require
           [clojure.string :as str :refer [trim]]
           [clojure.test]
           [fulcro-spec.assertions :as ae])))
    => '{str clojure.string, ae fulcro-spec.assertions}))
