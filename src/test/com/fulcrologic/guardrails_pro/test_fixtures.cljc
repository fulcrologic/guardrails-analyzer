(ns com.fulcrologic.guardrails-pro.test-fixtures
  (:require
    #?@(:cljs [[goog.string :refer [format]]
               [goog.string.format]])
    [clojure.string :as str]
    [clojure.test :as t]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [fulcro-spec.check :refer [checker]]
    [io.aviso.exception :as exc]
    [taoensso.timbre :as log]))

(def use-fixtures t/use-fixtures)

(defn- compact-ns [s]
  (str/join "."
    (let [segments (str/split s #"\.")]
      (conj
        (mapv #(str/join "-" (mapv first (str/split % #"\-")))
          (butlast segments))
        (last segments)))))

(defn- level->color-str [LEVEL]
  (log/color-str (case LEVEL
                   "DEBUG" :blue
                   "INFO" :green
                   "WARN" :yellow
                   ("ERROR" "FATAL") :red
                   :default)
    LEVEL))

(def default-test-frame-rules
  [[:package #"clojure\.lang.*" :omit]
   [:name #"clojure\.core.*" :omit]
   [:name #"kaocha\..*" :hide]
   [:name #"orchestra\..*" :hide]
   [:package #"java\.util\.concurrent.*" :hide]])

(defn test-exception-frame-filter [frame]
  [frame]
  (-> (keep #(#'exc/apply-rule frame %) default-test-frame-rules)
    first (or :show)))

(defn- test-output-fn [{:keys [level ?err _vargs msg_
                              ?ns-str ?file timestamp_ ?line]}]
  (str (log/color-str :black (force timestamp_)) " "
    (level->color-str (str/upper-case (name level)))  " "
    (log/color-str :blue "[" (or (format "%20s" (compact-ns ?ns-str)) ?file "?") ":" (or (format "%3s" ?line) "?") "] ")
    (force msg_)
    (when-let [err ?err]
      (binding [exc/*default-frame-filter* test-exception-frame-filter]
        (str "\n" (log/stacktrace err {}))))))

(def default-logging-config
  {:level          :debug
   :timestamp-opts {:pattern "HH:mm:ss.SSS"}
   :output-fn      test-output-fn})

(defn with-logging-config [config]
  (fn [f] (log/with-merged-config config (f))))

(defn with-default-test-logging-config [f]
  ((with-logging-config default-logging-config) f))

(defn capture-errors [f & args]
  (let [errors (atom [])]
    (with-redefs
      [grp.art/record-error! (fn [_ error] (swap! errors conj error))]
      (apply f args)
      @errors)))

(defn of-length?* [exp-len]
  (checker [actual]
    (let [length (count actual)]
      (when-not (= exp-len length)
        {:actual actual
         :expected `(~'of-length?* ~exp-len)
         :message (format "Expected count to be %d was %d" exp-len length)}))))
