(ns com.fulcrologic.guardrails-pro.core)

(defn process-defn [env form args]
  `(defn ~@args))

(defmacro >defn
  "Pro version of >defn. The non-pro version of this macro simply emits *this* macro if it is in pro mode."
  [& args]
  (process-defn &env &form args))
