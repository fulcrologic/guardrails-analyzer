(ns com.fulcrologic.copilot.analysis2.purity
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn => |]]
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as d]))

(defmulti pure?
  "A multimethod that can determine if an expression is pure,
   and therefore directly runnable during checking. Returns true if the expression
   is known to be pure, false otherwise."
  (fn [env expression] (d/analyze-dispatch env expression)))

(defmethod pure? :default [_env _expression] false)
(defmethod pure? :symbol.local/lookup [_env _expression] true)
(defmethod pure? :literal/wrapped [_env _expression] true)
(defmethod pure? :collection/vector [_env _expression] true)
(defmethod pure? :collection/set [_env _expression] true)
(defmethod pure? :collection/map [_env _expression] true)

;; TASK: IF is pure if its three parts are all pure
(defmethod pure? 'if [env expression] false)
(defmethod pure? 'clojure.core/if [env expression] false)
(defmethod pure? 'cljs.core/if [env expression] false)
