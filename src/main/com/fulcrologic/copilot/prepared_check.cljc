(ns com.fulcrologic.copilot.prepared-check
  (:require
    [clojure.tools.namespace.repl :as tools-ns]))

(tools-ns/disable-reload!)

(defonce prepared-check (atom nil))
