(ns com.fulcrologic.copilot.daemon.server.bindings
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.rpl.specter :as $]))

(defonce bindings (atom {}))

(defn get! [cid] (get @bindings cid))

(defn set! [cid new-bindings]
  (swap! bindings assoc cid new-bindings))

(defn clear! [cid]
  (swap! bindings assoc cid {}))

(defn default-encoder [binds] binds)

(defmulti encode-for-mm (fn [viewer-type _problems] viewer-type))

(defmethod encode-for-mm :default [_ binds] (default-encoder binds))

(defmethod encode-for-mm :IDEA [_ binds]
  (reduce (fn [acc {:as bind ::cp.art/keys [file line-start column-start]}]
            (update-in acc [file line-start column-start]
              (fnil conj [])
              (-> ($/transform [($/walker keyword?)] name bind)
                (assoc "severity" (namespace (::cp.art/problem-type bind))))))
    {} ($/select [($/walker ::cp.art/samples)] binds)))

(defn encode-for [{:keys [viewer-type]} binds]
  (encode-for-mm viewer-type binds))
