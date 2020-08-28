(ns com.fulcrologic.guardrails-pro.runtime.reporter
  (:require
    [com.fulcrologic.fulcro.application :as f.app]
    [com.fulcrologic.fulcro.components :as f.comp]
    [com.fulcrologic.fulcro.mutations :as f.m]
    #?(:cljs [com.fulcrologic.fulcro.networking.http-remote :as f.http])))

(f.m/defmutation set-problems [problems]
  (remote [env]
    (f.m/with-server-side-mutation env 'daemon/set-problems)))

(defonce app (f.app/fulcro-app
               #?(:cljs {:remotes {:remote (f.http/fulcro-http-remote {})}})))

(defn report-problems! [problems]
  (f.comp/transact! app
    [`(set-problems ~problems)]))
