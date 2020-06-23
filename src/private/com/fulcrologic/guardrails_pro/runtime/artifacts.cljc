(ns com.fulcrologic.guardrails-pro.runtime.artifacts
  "The runtime storage of artifacts to analyze. This namespace is what caches the forms in the runtime environment
  and acts as the central control for finding, caching, renewing and expiring things from the runtime. These routines
  must work in CLJ and CLJS, and should typically not be hot reloaded during updates.")

(defonce memory (atom {}))

(defn ^:export remember!
  "Remember the given `form` under key `s` (typically the function's FQ sym)."
  [s form]
  (swap! memory assoc s form))


