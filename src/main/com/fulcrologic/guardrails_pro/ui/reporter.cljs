(ns com.fulcrologic.guardrails-pro.ui.reporter
  (:require
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as f.m]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.ui.problem-formatter :refer [format-problems]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div h3 h4 label input]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [com.rpl.specter :as sp]
    [taoensso.timbre :as log]))

(f.m/defmutation focus-ns [{:keys [ns]}]
  (action [{:keys [app state]}]
    (swap! state assoc-in [:component/id ::namespace-problems :current-namespace] ns)
    (dr/change-route! app ["namespace"])))

(f.m/defmutation register-checker [_]
  (action [{:keys [state]}]
    (swap! state assoc :self-checker? true))
  (remote [env]
    (f.m/with-server-side-mutation env 'daemon/register-checker)))

(f.m/defmutation subscribe [_]
  (action [{:keys [state]}]
    (swap! state assoc :self-checker? false))
  (remote [env]
    (f.m/with-server-side-mutation env 'daemon/subscribe)))

(defn set-problems* [state-map problems]
  (let [by-sym (merge
                 (get-in problems [::grp.art/warnings ::grp.art/by-sym])
                 (get-in problems [::grp.art/errors ::grp.art/by-sym]))]
    (reduce-kv (fn [sm k v]
              (assoc-in sm [:problems/by-namespace (or (namespace k) "") (name k)] v))
      (dissoc state-map :problems/by-namespace)
      by-sym)))

(f.m/defmutation report-analysis [{:keys [bindings problems]}]
  (action [{:keys [state]}] (swap! state set-problems* problems))
  (remote [{:keys [state] :as env}]
    (when (get @state :self-checker?)
      (f.m/with-server-side-mutation env 'daemon/report-analysis))))

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

(defn ui-problem [{::grp.art/keys [problem-type message line-start]}]
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
                  (sort-by ::grp.art/line-start problems))))))
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

(declare update-problems!)
(defonce app
  (app/fulcro-app
    {:remotes {:remote (fws/fulcro-websocket-remote
                         {:push-handler
                          (fn [{:keys [topic msg]}]
                            (log/spy :info [topic msg])
                            (case topic
                              :new-problems
                              #_=> (update-problems! msg)
                              nil))})}}))

(defn update-problems! [problems]
  (log/info "received new problem list from daemon")
  (swap! (::app/state-atom app) set-problems* problems)
  (app/schedule-render! app))

(defn hot-reload! [] (app/mount! app CheckerRoot "checker"))

(defn start!
  ([] (start! false))
  ([checker?]
   (log/info "Starting checker app")
   (hot-reload!)
   (if checker?
     (comp/transact! app [(register-checker)])
     (comp/transact! app [(subscribe)]))))

(defn transit-safe-problems [problems]
  (sp/transform (sp/walker ::grp.art/problem-type)
    (fn [problem]
      (let [ok-keys [::grp.art/problem-type
                     ::grp.art/message
                     ::grp.art/file
                     ::grp.art/line-start
                     ::grp.art/line-end
                     ::grp.art/column-end
                     ::grp.art/column-start]]
        (select-keys problem ok-keys)))
    problems))

(defn formatted-bindings [bindings]
  (reduce-kv
    (fn [acc location {::grp.art/keys [type samples original-expression]}]
      (let [pp-samples (mapv (fn [s] (with-out-str (pprint s))) samples)]
        (assoc acc location {:type       type
                             :expression (pr-str original-expression)
                             :samples    pp-samples})))
    {} bindings))

(defn report-analysis! []
  (let [problems (-> @grp.art/problems format-problems transit-safe-problems)
        bindings (formatted-bindings @grp.art/binding-annotations)]
    (comp/transact! app [(report-analysis {:problems problems
                                           :bindings bindings})])))
