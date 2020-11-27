(ns com.fulcrologic.guardrails-pro.daemon.server.problems
  (:require
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologicpro.com.rpl.specter :as $]))

(defonce problems (atom nil))

(defn get! [] @problems)

(defn set! [new-problems]
  (reset! problems new-problems))

(defn clear! []
  (reset! problems {}))

(defn default-encoder [problems] problems)

(defmulti encode-for-mm (fn [viewer-type _problems] viewer-type))

(defmethod encode-for-mm :default [_ problems] (default-encoder problems))

(defmethod encode-for-mm :IDEA [_ problems]
  (reduce (fn [acc {:as problem ::grp.art/keys [file line-start column-start]}]
            (update-in acc [file line-start column-start]
              (fnil conj [])
              (-> ($/transform [($/walker keyword?)] name problem)
                (assoc "severity" (namespace (::grp.art/problem-type problem))))))
    {} ($/select [($/walker ::grp.art/problem-type)] problems)))

(defn encode-for [{:keys [viewer-type]} problems]
  (encode-for-mm viewer-type problems))
