(ns com.fulcrologic.guardrails-pro.ui.viewer
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as f.m]
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.guardrails-pro.ui.shared :as ui.shared]
    [taoensso.timbre :as log]))

(f.m/defmutation subscribe [_]
  (remote [env]
    (f.m/with-server-side-mutation env 'daemon/subscribe)))

(defrouter ViewerRouter [this props]
  {:router-targets [ui.shared/AllProblems ui.shared/NamespaceProblems]})

(def ui-viewer-router (comp/factory ViewerRouter))

(defsc ViewerRoot [this {:root/keys [router]}]
  {:query         [{:root/router (comp/get-query ViewerRouter)}]
   :initial-state {:root/router {}}}
  (dom/div :.ui.container
    (dom/div :.ui.top.menu
      (dom/div :.item {:onClick (fn [] (dr/change-route! this ["all"]))}
        (dom/a {} "All Issues")))
    (ui-viewer-router router)))

(defonce app (atom nil))

(defn set-problems! [problems]
  (swap! (::app/state-atom @app)
    ui.shared/set-problems* problems))

(defn set-bindings! [bindings]
  (swap! (::app/state-atom @app)
    ui.shared/set-bindings* bindings))

(defn hot-reload! []
  (app/mount! @app ViewerRoot "viewer"))

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
               :clear!       (set-problems! [])
               :new-problems (set-problems! msg)
               :new-bindings (set-bindings! msg)
               :up-to-date   (do (log/info "Up to date with daemon!")
                               (app/schedule-render! @app))
               (log/error "invalid websocket message of type:" topic)))})}}))
  (hot-reload!)
  (comp/transact! @app [(subscribe {:viewer-type :browser})]))
