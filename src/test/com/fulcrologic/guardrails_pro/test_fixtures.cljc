(ns com.fulcrologic.guardrails-pro.test-fixtures
  (:require
    [clojure.test :as t]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails-pro.test-fixtures.logging :as tf.log]
    [taoensso.timbre :as log]))

(def use-fixtures t/use-fixtures)

(def default-logging-config
  {:level          :debug
   :timestamp-opts {:pattern "HH:mm:ss.SSS"}
   :output-fn      tf.log/test-output-fn})

(defn with-logging-config [config]
  (fn [f] (log/with-merged-config config (f))))

(defn with-default-test-logging-config [f]
  ((with-logging-config default-logging-config) f))

(def test-logs (atom []))

(defmacro with-record-logs [config & body]
  `(log/with-merged-config
     (merge {:level :trace
             :appenders {:test-appender (tf.log/test-appender test-logs)}}
       ~config)
     (reset! test-logs [])
     ~@body))

(defn test-env [& args]
  (-> (grp.art/build-env)
    (merge {::grp.art/checking-sym 'fake-sym
            ::grp.art/checking-file "fake-file"
            ::grp.art/current-ns "fake-ns"
            ::grp.art/location #::grp.art{:line-start 1
                                          :column-start 1}})
    (grp.spec/with-spec-impl :clojure.spec.alpha
      {:cache-samples? false})))

(defn capture-errors [f & args]
  (let [errors (atom [])
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
        record! grp.art/record-warning!]
    (with-redefs
      [grp.art/record-warning! (fn [& args]
                                 (if (= 2 (count args))
                                   (swap! warnings conj (second args))
                                   (apply record! args))
                                 nil)]
      (apply f args)
      @warnings)))
