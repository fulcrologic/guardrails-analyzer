(ns ^:no-doc com.fulcrologicpro.fulcro.inspect.preload
  "Namespace to use in your compiler preload in order to enable inspect support during development."
  (:require
    [com.fulcrologicpro.fulcro.inspect.inspect-client :as inspect]))

(inspect/install {})
