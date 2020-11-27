(ns com.fulcrologic.guardrails-pro.daemon.server.bindings
  (:require
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologicpro.com.rpl.specter :as $]))

(defonce bindings (atom nil))

(defn get! [] @bindings)

(defn set! [new-bindings]
  (reset! bindings new-bindings))

(defn clear! []
  (reset! bindings {}))

(defn default-encoder [binds] binds)

(defmulti encode-for-mm (fn [viewer-type _problems] viewer-type))

(defmethod encode-for-mm :default [_ binds] (default-encoder binds))

(defmethod encode-for-mm :IDEA [_ binds]
  (reduce (fn [acc {:as bind ::grp.art/keys [file line-start column-start]}]
            (update-in acc [file line-start column-start]
              (fnil conj [])
              (-> ($/transform [($/walker keyword?)] name bind)
                (assoc "severity" (namespace (::grp.art/problem-type bind))))))
    {} ($/select [($/walker ::grp.art/samples)] binds)))

(defn encode-for [{:keys [viewer-type]} binds]
  (encode-for-mm viewer-type binds))
