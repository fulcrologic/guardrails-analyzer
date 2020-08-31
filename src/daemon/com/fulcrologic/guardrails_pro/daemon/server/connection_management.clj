(ns com.fulcrologic.guardrails-pro.daemon.server.connection-management
  (:require
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [mount.core :refer [defstate]]
    [taoensso.timbre :as log]
    [clojure.set :as set]
    [com.fulcrologic.fulcro.networking.websocket-protocols :as wsp]
    [com.fulcrologic.guardrails-pro.daemon.server.problems :as problems]))

(defstate connected-clients :start (atom #{}))

(def daemon-cids (atom #{}))

(def listener (reify wsp/WSListener
                (client-added [_ _ cid]
                  (swap! connected-clients conj cid))
                (client-dropped [_ _ cid]
                  (swap! connected-clients disj cid))))

(defn notify-daemon-uis! [websockets]
  (let [daemons (set/intersection @daemon-cids @connected-clients)]
    (log/info daemons)
    (doseq [d daemons]
      (log/info "Notifying daemon UI of new problems " d)
      (wsp/push websockets d :new-problems (problems/get!)))))

