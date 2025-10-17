(ns com.fulcrologic.guardrails-analyzer.daemon.server.problems
  (:require
    [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
    [com.rpl.specter :as $]))

(defonce problems (atom {}))

(defn get! [cid] (get @problems cid))

(defn set! [cid new-problems]
  (swap! problems assoc cid new-problems))

(defn clear! [cid]
  (swap! problems assoc cid {}))

(defn default-encoder [problems] problems)

(defmulti encode-for-mm (fn [viewer-type _problems] viewer-type))

(defmethod encode-for-mm :default [_ problems] (default-encoder problems))

(defmethod encode-for-mm :IDEA [_ problems]
  (reduce (fn [acc {:as problem ::cp.art/keys [file line-start column-start]}]
            (update-in acc [file line-start column-start]
              (fnil conj [])
              (-> ($/transform [($/walker keyword?)] name problem)
                (assoc "severity" (namespace (::cp.art/problem-type problem))))))
    {} ($/select [($/walker ::cp.art/problem-type)] problems)))

(defn encode-for [{:keys [viewer-type]} problems]
  (encode-for-mm viewer-type problems))
