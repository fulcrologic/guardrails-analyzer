;; Copyright (c) Fulcrologic, LLC. All rights reserved.
;;
;; Permission to use this software requires that you
;; agree to our End-user License Agreement, legally obtain a license,
;; and use this software within the constraints of the terms specified
;; by said license.
;;
;; You may NOT publish, redistribute, or reproduce this software or its source
;; code in any form (printed, electronic, or otherwise) except as explicitly
;; allowed by your license agreement..

(ns com.fulcrologic.copilot.ui.shared
  (:require
    [com.fulcrologicpro.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologicpro.fulcro.dom :as dom :refer [div h3 h4 label input]]
    [com.fulcrologicpro.fulcro.mutations :as f.m]
    [com.fulcrologicpro.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.copilot.artifacts :as cp.art]))

(f.m/defmutation focus-ns [{:keys [ns]}]
  (action [{:keys [app state]}]
    (swap! state assoc-in [:component/id ::namespace-problems :current-namespace] ns)
    (dr/change-route! app ["namespace"])))

(defn set-problems* [state problems]
  (reduce
    (fn [s {:as p ::cp.art/keys [NS sym]}]
      (update-in s
        [:problems/by-namespace NS (name sym)]
        (fnil conj [])
        p))
    (dissoc state :problems/by-namespace)
    problems))

(defn set-bindings* [state bindings] ;; TODO ??
  (assoc state :bindings bindings))

(defsc Settings [this {:settings/keys [daemon-port]}]
  {:query         [:settings/daemon-port]
   :initial-state {:settings/daemon-port 9000}
   :route-segment ["settings"]
   :ident         (fn [] [:component/id ::settings])}
  (div
    (h3 "Settings")
    (div :.ui.form
      (div :.field
        (label "Daemon Port")
        (input {:type     "number"
                :value    daemon-port
                :onChange (fn [evt] (f.m/set-integer!! this :settings/daemon-port :event evt))})))))

(defn ui-problem [{:as problem ::cp.art/keys [problem-type message line-start]}]
  (div :.item {:key message}
    (dom/i :.exclamation.icon
      {:classes [(case (namespace problem-type)
                   "error"   "red"
                   "warning" "yellow"
                   nil)]})
    (str line-start ": " message)))

(defn namespace-problem-list [this ns ns-problems]
  (div :.ui.segment {:key ns}
    (h4 (dom/a {:href "#" :onClick (fn [] (comp/transact! this [(focus-ns {:ns ns})]))} ns))
    (div :.ui.list
      (mapv
        (fn [[fname problems]]
          (comp/fragment
            (div :.item (dom/h4 fname))
            (when (seq problems)
              (div :.list
                (mapv ui-problem
                  (sort-by ::cp.art/line-start problems))))))
        ns-problems))))

(defsc NamespaceProblems [this {:keys [problems/by-namespace current-namespace]}]
  {:query         [:current-namespace
                   [:problems/by-namespace '_]]
   :initial-state {:current-namespace ""}
   :ident         (fn [] [:component/id ::namespace-problems])
   :route-segment ["namespace"]}
  (div
    (h3 "Problems for " current-namespace)
    (namespace-problem-list this current-namespace
      (get by-namespace current-namespace))))

(defsc AllProblems [this {:keys [problems/by-namespace show-warnings?]}]
  {:query         [:show-warnings?
                   [:problems/by-namespace '_]]
   :initial-state {:show-warnings? true}
   :ident         (fn [] [:component/id ::all-problems])
   :route-segment ["all"]}
  (let [nses (sort (keys by-namespace))]
    (div
      (h3 "All Problems")
      (mapv (fn [ns] (namespace-problem-list this ns (get by-namespace ns))) nses))))
