(ns com.fulcrologic.guardrails-pro.daemon.server.bindings
  (:require
    [com.fulcrologic.guardrails-pro.static.clojure-reader :as clj-reader]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [taoensso.timbre :as log]))

(defonce bindings (atom {}))

(defn get!
  ([] @bindings)
  ([file]
   (into {}
     (filter (fn [[{problem-file ::grp.art/file} _]]
               (= problem-file file)))
     (get!))))

(defn set! [new-bindings]
  (reset! bindings new-bindings)
  (log/info "Set bindings " (count @bindings)))

(defn clear! []
  (reset! bindings {}))

