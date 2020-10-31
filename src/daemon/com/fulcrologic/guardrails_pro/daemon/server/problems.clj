(ns com.fulcrologic.guardrails-pro.daemon.server.problems
  (:require
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.rpl.specter :as $]))

(defonce problems (atom {}))

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
              {"message"      (::grp.art/message problem)
               "severity"     (namespace (::grp.art/problem-type problem))
               "expression"   (::grp.art/expression problem)
               "line-start"   (::grp.art/line-start problem)
               "line-end"     (::grp.art/line-end problem)
               "column-start" (::grp.art/column-start problem)
               "column-end"   (::grp.art/column-end problem)}))
    {} ($/select [($/walker ::grp.art/problem-type)] problems)))

(defn encode-for [{:keys [viewer-type]} problems]
  (encode-for-mm viewer-type problems))
