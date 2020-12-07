(ns com.fulcrologic.copilot.test-fixtures
  (:require
    [clojure.test :as t]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.analysis.spec :as cp.spec]
    [com.fulcrologic.copilot.test-fixtures.logging :as tf.log]
    [com.fulcrologicpro.taoensso.timbre :as log]))

;; DOES NOT WORK IN CLJS
;; (def use-fixtures t/use-fixtures)

(defn with-logging-config [config]
  (fn [f] (f)))

(defn with-default-test-logging-config [f]
  ((with-logging-config {}) f))

(def test-logs (atom []))

(defn with-record-logs [config & body]
  (fn [f] (f)))

(defn test-env [& args]
  (-> (cp.art/build-env)
    (merge {::cp.art/checking-sym  'fake-sym
            ::cp.art/checking-file "fake-file"
            ::cp.art/current-ns    "fake-ns"
            ::cp.art/location      #::cp.art{:line-start   1
                                               :column-start 1}})
    (cp.spec/with-spec-impl :clojure.spec.alpha
      {:cache-samples? false})))

(defn capture-errors [f & args]
  (let [errors  (atom [])
        record! cp.art/record-error!]
    (with-redefs
      [cp.art/record-error! (fn [& args]
                               (if (= 2 (count args))
                                 (swap! errors conj (second args))
                                 (apply record! args))
                               nil)]
      (apply f args)
      @errors)))

(defn capture-warnings [f & args]
  (let [warnings (atom [])
        record!  cp.art/record-warning!]
    (with-redefs
      [cp.art/record-warning! (fn [& args]
                                 (if (= 2 (count args))
                                   (swap! warnings conj (second args))
                                   (apply record! args))
                                 nil)]
      (apply f args)
      @warnings)))

(defn capture-bindings [f & args]
  (let [bindings (atom [])]
    (with-redefs
      [cp.art/record-binding! (fn [env sym td]
                                 (swap! bindings conj td))]
      (apply f args)
      @bindings)))
