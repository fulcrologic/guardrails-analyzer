(ns com.fulcrologic.guardrails-pro.runtime.reporter
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as f.m]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.dom :as dom :refer [div h3 h4 label input]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [com.fulcrologic.fulcro.mutations :as m]))

(f.m/defmutation focus-ns [{:keys [ns]}]
  (action [{:keys [app state]}]
    (swap! state assoc-in [:component/id ::namespace-problems :current-namespace] ns)
    (dr/change-route! app ["namespace"])))

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
                :onChange (fn [evt] (m/set-integer!! this :settings/daemon-port :event evt))})))))

(defn namespace-problem-list [this ns ns-problems]
  (div :.ui.segment {:key ns}
    (h4 (dom/a {:href "#" :onClick (fn [] (comp/transact! this [(focus-ns {:ns ns})]))} ns))
    (div :.ui.list
      (mapv
        (fn [[fname {::grp.art/keys [errors warnings] :as problems}]]
          (let [errors   (sort-by ::grp.art/line-number errors)
                warnings (sort-by ::grp.art/line-number warnings)]
            (comp/fragment
              (div :.item (dom/h4 fname))
              (when (seq errors)
                (comp/fragment
                  (div :.list
                    (mapv
                      (fn [{::grp.art/keys [line-number message]}]
                        (div :.item {:key message}
                          (dom/i :.red.exclamation.icon)
                          (str line-number ": " message)))
                      errors))))
              (when (seq warnings)
                (comp/fragment
                  (div :.list
                    (mapv
                      (fn [{::grp.art/keys [line-number message]}]
                        (div :.item {:key message}
                          (dom/i :.yellow.exclamation.icon)
                          (str line-number ": " message)))
                      warnings)))))))
        ns-problems))))

(defsc NamespaceProblems [this {:keys [problems/by-namespace current-namespace]}]
  {:query         [:current-namespace
                   [:problems/by-namespace '_]]
   :initial-state {:current-namespace ""}
   :ident         (fn [] [:component/id ::namespace-problems])
   :route-segment ["namespace"]}
  (div
    (h3 "Problems for " current-namespace)
    (namespace-problem-list this current-namespace (get by-namespace current-namespace))))



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

(defrouter CheckerRouter [this props]
  {:router-targets [AllProblems NamespaceProblems Settings]})

(def ui-checker-router (comp/factory CheckerRouter))

(defsc CheckerRoot [this {:root/keys [router]}]
  {:query         [{:root/router (comp/get-query CheckerRouter)}]
   :initial-state {:root/router {}}}
  (div :.ui.container
    (div :.ui.top.menu
      (div :.item {:onClick (fn [] (dr/change-route! this ["all"]))} (dom/a {} "All Issues"))
      (div :.item {:onClick (fn [] (dr/change-route! this ["settings"]))} (dom/a {} "Settings")))
    (ui-checker-router router)))

(def ui-reporter-root (comp/factory CheckerRoot {:keyfn :id}))

(f.m/defmutation set-problems [problems]
  (action [{:keys [state]}]
    (let [ks (keys problems)]
      (swap! state (fn [s]
                     (reduce
                       (fn [sm k]
                         (assoc-in sm [:problems/by-namespace (or (namespace k) "") (name k)] (get problems k)))
                       (dissoc s :problems/by-namespace)
                       ks)))))
  (remote [env]
    (f.m/with-server-side-mutation env 'daemon/set-problems)))

(defonce app (app/fulcro-app {:remotes {:remote (fws/fulcro-websocket-remote {})}}))

(defn start! []
  (log/info "Starting checker app")
  (app/mount! app CheckerRoot "checker"))

(defn report-problems! [problems]
  (comp/transact! app [`(set-problems ~problems)]))
