(ns com.fulcrologic.guardrails-analyzer.test-fixtures
  (:require
    [clojure.test :as t]
    [com.fulcrologic.guardrails-analyzer.analysis.spec :as cp.spec]
    [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
    [com.fulcrologic.guardrails-analyzer.test-fixtures.logging :as tf.log]
    [com.fulcrologicpro.taoensso.timbre :as log]))

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
     (merge {:level     :trace
             :appenders {:test-appender (tf.log/test-appender test-logs)}}
       ~config)
     (reset! test-logs [])
     ~@body))

(defn test-env []
  (-> (cp.art/build-env {:NS   "fake-ns"
                         :file "fake-file"})
    (merge {::cp.art/checking-sym 'fake-sym
            ::cp.art/location     #::cp.art{:line-start   1
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
