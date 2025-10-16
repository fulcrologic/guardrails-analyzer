(ns com.fulcrologic.copilot.system-test
  (:require
    [clojure.test :as t]
    [com.fulcrologic.copilot.test-cases-runner :as tcr]
    [com.fulcrologicpro.fulcro.application :as f.app]
    [com.fulcrologicpro.fulcro.components :as f.comp]
    [com.fulcrologicpro.fulcro.mutations :as f.mut :refer [defmutation]]
    [com.fulcrologicpro.fulcro.networking.websockets-client :as f.ws]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defonce APP (f.app/headless-synchronous-app {}))

(defmutation subscribe [_]
  (remote [env]
    (f.mut/with-server-side-mutation env 'daemon/subscribe)))

(defmutation check-file [params]
  (remote [env]
    (f.mut/with-server-side-mutation env 'daemon/check-current-file))
  (ok-action [{:keys [result state]}]
    (if (= :no-checkers
          (get-in result [:body 'daemon/check-current-file :error]))
      (do (Thread/sleep 1000)
          (f.comp/transact! APP
            [(check-file params)]))
      (swap! state assoc :checking-file (:file params)))))

(defmutation set-problems [{:keys [problems]}]
  (action [{:keys [state]}]
    (swap! state assoc :problems problems)))

(defmutation set-bindings [{:keys [bindings]}]
  (action [{:keys [state]}]
    (swap! state assoc :bindings bindings)))

(defn report-failure! [m]
  (log/error "System Test failed to run smoke test:")
  (if (= :error (:type m))
    (log/error (:actual m) "Unexpected error:")
    (t/do-report m))
  (System/exit 7))

(defmutation run-test-case [_]
  (action [{:keys [state]}]
    (let [tc-file "src/system_test/com/fulcrologic/copilot/smoke_test.clj"]
      (if-not (:checking-file @state)
        (f.comp/transact! APP [(check-file {:file tc-file})])
        (do (tcr/run-tc-file! report-failure! tc-file @state)
            (System/exit 0))))))

(defn start [{:as opts :keys [port]}]
  (log/info ::started opts)
  (f.app/set-remote! APP :remote
    (f.ws/fulcro-websocket-remote
      {:host          (str "localhost:" port)
       :sente-options {:csrf-token-fn (fn [] nil)}
       :push-handler  (fn [{:keys [topic msg]}]
                        (case topic
                          :new-problems
                          (f.comp/transact! APP
                            [(set-problems {:problems msg})])
                          :new-bindings
                          (f.comp/transact! APP
                            [(set-bindings {:bindings msg})])
                          :up-to-date
                          (f.comp/transact! APP
                            [(run-test-case)])
                          nil))}))
  (f.comp/transact! APP
    [(subscribe {:checker-type :system-test
                 :project-dir  (System/getProperty "user.dir")})])
  @(promise))
