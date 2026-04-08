(ns com.fulcrologic.guardrails-analyzer.daemon.server.problems
  (:require
   [clojure.walk :as walk]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]))

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
  (let [problems-with-type (filterv #(and (map? %) (::cp.art/problem-type %)) problems)]
    (reduce (fn [acc {:as problem ::cp.art/keys [file line-start column-start]}]
              (update-in acc [file line-start column-start]
                         (fnil conj [])
                         (-> (walk/postwalk (fn [node] (if (keyword? node) (name node) node)) problem)
                             (assoc "severity" (namespace (::cp.art/problem-type problem))))))
            {} problems-with-type)))

(defn encode-for [{:keys [viewer-type]} problems]
  (encode-for-mm viewer-type problems))
