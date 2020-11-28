(ns com.fulcrologic.copilot.test-fixtures
  (:require
    [clojure.test :as t]
    [com.fulcrologic.copilot.artifacts :as grp.art]
    [com.fulcrologic.copilot.analysis.spec :as grp.spec]
    [com.fulcrologic.copilot.test-fixtures.logging :as tf.log]
    [com.fulcrologic.copilot.logging :as log]))

(def use-fixtures t/use-fixtures)

(defn with-logging-config [config]
  (fn [f] (f)))

(defn with-default-test-logging-config [f]
  ((with-logging-config {}) f))

(def test-logs (atom []))

(defn with-record-logs [config & body]
  (fn [f] (f)))

(defn test-env [& args]
  (-> (grp.art/build-env)
    (merge {::grp.art/checking-sym  'fake-sym
            ::grp.art/checking-file "fake-file"
            ::grp.art/current-ns    "fake-ns"
            ::grp.art/location      #::grp.art{:line-start   1
                                               :column-start 1}})
    (grp.spec/with-spec-impl :clojure.spec.alpha
      {:cache-samples? false})))

(defn capture-errors [f & args]
  (let [errors  (atom [])
        record! grp.art/record-error!]
    (with-redefs
      [grp.art/record-error! (fn [& args]
                               (if (= 2 (count args))
                                 (swap! errors conj (second args))
                                 (apply record! args))
                               nil)]
      (apply f args)
      @errors)))

(defn capture-warnings [f & args]
  (let [warnings (atom [])
        record!  grp.art/record-warning!]
    (with-redefs
      [grp.art/record-warning! (fn [& args]
                                 (if (= 2 (count args))
                                   (swap! warnings conj (second args))
                                   (apply record! args))
                                 nil)]
      (apply f args)
      @warnings)))

(defn capture-bindings [f & args]
  (let [bindings (atom [])]
    (with-redefs
      [grp.art/record-binding! (fn [env sym td]
                                 (swap! bindings conj td))]
      (apply f args)
      @bindings)))
