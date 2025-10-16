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

(ns com.fulcrologic.copilot.ui.checker
  (:require
    [com.fulcrologic.copilot.checker :as cp.checker]
    [com.fulcrologic.copilot.ui.shared :as ui.shared]
    [com.fulcrologicpro.fulcro.application :as app]
    [com.fulcrologicpro.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologicpro.fulcro.dom :as dom]
    [com.fulcrologicpro.fulcro.mutations :as f.m]
    [com.fulcrologicpro.fulcro.networking.websockets :as fws]
    [com.fulcrologicpro.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologicpro.taoensso.timbre :as log]))

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

(defn DBG_ENV! [])

(defn report-analysis! []
  (let [analysis (cp.checker/gather-analysis!)]
    (comp/transact! @app [(report-analysis analysis)])))

(defn check! [msg]
  (DBG_ENV!)
  (try
    (cp.checker/check! msg
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
