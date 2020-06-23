(ns com.fulcrologic.guardrails-pro.core
  (:require
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]))

(defn process-defn [env form args]
  `(defn ~@args))

(defmacro >defn
  "Pro version of >defn. The non-pro version of this macro simply emits *this* macro if it is in pro mode."
  [& args]
  (process-defn &env &form args))

(comment
  (a/remember! 'x '()))
