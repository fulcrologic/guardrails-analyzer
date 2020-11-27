(ns com.fulcrologic.guardrails-pro.ui.checker
  (:require
    [com.fulcrologicpro.fulcro.application :as app]
    [com.fulcrologicpro.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologicpro.fulcro.dom :as dom]
    [com.fulcrologicpro.fulcro.mutations :as f.m]
    [com.fulcrologicpro.fulcro.networking.websockets :as fws]
    [com.fulcrologicpro.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.checker :as grp.checker]
    [com.fulcrologic.guardrails-pro.ui.shared :as ui.shared]
    [com.fulcrologic.guardrails-pro.logging :as log]))

(f.m/defmutation register-checker [_]
  (remote [env]
    (f.m/with-server-side-mutation env 'daemon/register-checker)))

(f.m/defmutation report-analysis [{:keys [bindings problems]}]
  (action [{:keys [state]}]
    (swap! state ui.shared/set-problems* problems)
    (swap! state ui.shared/set-bindings* bindings))
  (remote [{:keys [state] :as env}]
    (f.m/with-server-side-mutation env 'daemon/report-analysis)))

(defrouter CheckerRouter [this props]
  {:router-targets [ui.shared/AllProblems ui.shared/NamespaceProblems ui.shared/Settings]})

(def ui-checker-router (comp/factory CheckerRouter))

(defsc CheckerRoot [this {:root/keys [router]}]
  {:query         [{:root/router (comp/get-query CheckerRouter)}]
   :initial-state {:root/router {}}}
  (dom/div :.ui.container
    (dom/div :.ui.top.menu
      (dom/div :.item {:onClick (fn [] (dr/change-route! this ["all"]))}
        (dom/a {} "All Issues"))
      (dom/div :.item {:onClick (fn [] (dr/change-route! this ["settings"]))}
        (dom/a {} "Settings")))
    (ui-checker-router router)))

(defonce app (atom nil))

;; TODO: remove or config + disabled by default
(defn DBG_ENV! []
  (let [env (grp.art/build-env)]
    (js/setTimeout #(tap> env) 2000)))

(defn report-analysis! []
  (let [analysis (grp.checker/gather-analysis!)]
    (comp/transact! @app [(report-analysis analysis)])))

(defn check! [msg]
  (DBG_ENV!)
  (try
    (grp.checker/check! msg
      #(report-analysis!))
    (catch :default e
      (log/error e "Failed to check!"))))

(defn hot-reload! []
  (DBG_ENV!)
  (app/mount! @app CheckerRoot "checker"))

(defn start!
  [{:keys [host port]}]
  (log/info "Starting checker app")
  (reset! app
    (app/fulcro-app
      {:remotes
       {:remote
        (fws/fulcro-websocket-remote
          {:sente-options {:host host :port port}
           :push-handler
           (fn [{:keys [topic msg]}]
             (log/spy :info topic)
             (case topic
               :check! (check! msg)
               (log/error "invalid websocket message of type:" topic)))})}}))
  (hot-reload!)
  (comp/transact! @app [(register-checker {:checker-type :cljs})]))
