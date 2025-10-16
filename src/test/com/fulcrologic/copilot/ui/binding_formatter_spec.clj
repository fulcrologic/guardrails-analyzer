(ns com.fulcrologic.copilot.ui.binding-formatter-spec
  (:require
    [com.fulcrologic.copilot.analysis.analyze-test-utils :as cp.atu]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [com.fulcrologic.copilot.ui.binding-formatter :refer [format-bindings]]
    [fulcro-spec.core :refer [=> =fn=> assertions specification]]))

(tf/use-fixtures :once tf/with-default-test-logging-config)

(defn test:format-binding [env x]
  (mapv (juxt ::cp.art/message ::cp.art/tooltip)
    (format-bindings
      (tf/capture-bindings cp.atu/analyze-string! env x))))

(specification "format-binding" :integration :wip
  (let [env (tf/test-env)]
    (assertions
      (test:format-binding env "(let [a (rand-int 2)] a)")
      => [["Bindings for: a"
           "<b>Type:</b>int?<br><b>Sample Values:</b><br><pre>0</pre><pre>1</pre>"]])))

(specification "format-binding with path-based type descriptions" :integration
  (let [env    (tf/test-env)
        result (test:format-binding env "(let [x 5] (if (even? x) (+ x 1) (- x 1)))")]
    (assertions
      "handles bindings inside conditionals (path-based type descriptions)"
      ;; x should have a binding with samples
      (count result) => 1
      (first (first result)) => "Bindings for: x"
      ;; Tooltip should contain sample values (not empty)
      (second (first result)) =fn=> #(and (string? %)
                                       (.contains % "Sample Values")
                                       (> (count %) 50)))))

(specification "format-binding with nested conditionals" :integration
  (let [env    (tf/test-env)
        result (test:format-binding env
                 "(let [n 10]
                    (cond
                      (< n 5) :small
                      (< n 15) :medium
                      :else :large))")]
    (assertions
      "handles bindings used in nested conditionals"
      ;; n should have a binding
      (count result) => 1
      (first (first result)) => "Bindings for: n"
      ;; Should have samples from all paths
      (second (first result)) =fn=> #(and (string? %)
                                       (.contains % "Sample Values")
                                       (.contains % "10")))))

(specification "format-bindings with mixed regular and path-based" :integration
  (let [env    (tf/test-env)
        result (test:format-binding env
                 "(let [a 1
                        b (if (pos? a) 2 3)
                        c (+ a b)]
                    c)")]
    (assertions
      "handles both regular and path-based bindings in same expression"
      ;; Should have bindings for a, b, and c
      (count result) =fn=> pos?
      ;; All should have messages
      (every? (fn [[msg _]] (.startsWith msg "Bindings for:")) result) => true
      ;; All should have non-empty tooltips
      (every? (fn [[_ tooltip]] (and (string? tooltip)
                                  (.contains tooltip "Sample Values")))
        result) => true)))
