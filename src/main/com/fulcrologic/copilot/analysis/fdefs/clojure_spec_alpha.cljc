;; Copyright (c) Fulcrologic, LLC. All rights reserved.
;;
;; Permission to use this software requires that you
;; agree to our End-user License Agreement, legally obtain a license,
;; and use this software within the constraints of the terms specified
;; by said license.
;;
;; You may NOT publish, redistribute, or reproduce this software or its source
;; code in any form (printed, electronic, or otherwise) except as explicitly
;; allowed by your license agreement..

(ns com.fulcrologic.copilot.analysis.fdefs.clojure-spec-alpha
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test.check.generators]
    [com.fulcrologic.copilot.analysis.analyzer :refer [defanalyzer]]))

(s/def ::spec-key (s/or :kw qualified-keyword? :sym symbol?))

(s/def ::spec-val (s/or :kw qualified-keyword? :spec s/spec? :pred ifn? :regex-op s/regex?))

(defanalyzer clojure.spec.alpha/def [env expr] {})
