(ns com.fulcrologic.copilot.analysis.function-type-spec
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.analysis.function-type :as cp.fnt]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [fulcro-spec.core :refer [specification assertions]]
    [fulcro-spec.check :as _]))

(tf/use-fixtures :once tf/with-default-test-logging-config)
