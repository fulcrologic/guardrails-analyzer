(ns com.fulcrologic.copilot.checker-spec
  (:require
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification assertions]]
    [com.fulcrologic.copilot.analysis.analyzer :as grp.ana]
    [com.fulcrologic.copilot.checker :as grp.checker]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [com.fulcrologic.copilot.artifacts :as grp.art]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn test:gather-analysis! [env x]
  (grp.art/clear-bindings!)
  (grp.art/clear-problems!)
  (grp.ana/analyze! env x)
  (grp.checker/gather-analysis!))

(specification "gather-analysis!" :integration
  (let [env (tf/test-env)]
    (assertions
      (test:gather-analysis! env `(let [a# 1] a#))
      =check=> (_/embeds?*
                 {:bindings
                  (_/seq-matches?*
                    [(_/embeds?* {::grp.art/spec ::_/not-found})])})
      ;; TODO: recursive-description
      )))
