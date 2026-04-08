(ns ^:clj-reload/no-reload com.fulcrologic.guardrails-analyzer.daemon.server.bindings
  (:require
   [clojure.walk :as walk]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]))

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
  (let [binds-with-samples (filterv #(and (map? %) (::cp.art/samples %)) binds)]
    (reduce (fn [acc {:as bind ::cp.art/keys [file line-start column-start]}]
              (update-in acc [file line-start column-start]
                         (fnil conj [])
                         (-> (walk/postwalk (fn [node] (if (keyword? node) (name node) node)) bind)
                             (assoc "severity" (namespace (::cp.art/problem-type bind))))))
            {} binds-with-samples)))

(defn encode-for [{:keys [viewer-type]} binds]
  (encode-for-mm viewer-type binds))
