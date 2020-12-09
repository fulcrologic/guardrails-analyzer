(ns com.fulcrologic.copilot.checker-spec
  (:require
    [com.fulcrologic.copilot.analysis.analyzer :as cp.ana]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.checker :as cp.checker]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn test:gather-analysis! [env x]
  (cp.art/clear-bindings!)
  (cp.art/clear-problems!)
  (cp.ana/analyze! env x)
  (cp.checker/gather-analysis!))

(specification "gather-analysis!" :integration
  (let [env (tf/test-env)]
    (assertions
      (test:gather-analysis! env `(let [a# 1] a#))
      =check=> (_/embeds?*
                 {:bindings
                  (_/seq-matches?*
                    [(_/embeds?* {::cp.art/spec ::_/not-found})])})
      ;; TODO: recursive-description
      )))
