(ns com.fulcrologic.copilot.ui.binding-formatter-spec
  (:require
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [com.fulcrologic.copilot.analysis.analyzer :as grp.ana]
    [com.fulcrologic.copilot.artifacts :as grp.art]
    [com.fulcrologic.copilot.ui.binding-formatter :refer [format-bindings]]
    [fulcro-spec.core :refer [specification assertions]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn test:format-binding [env x]
  (mapv (juxt ::grp.art/message ::grp.art/tooltip)
    (format-bindings
      (tf/capture-bindings grp.ana/analyze! env x))))

(specification "format-binding" :integration :wip
  (let [env (tf/test-env)]
    (assertions
      (test:format-binding env `(let [~'a (rand-int 2)] ~'a))
      => [["Bindings for: a"
           "<b>Type:</b>int?<br><b>Sample Values:</b><br><pre>0</pre><pre>1</pre>"]])))
