(ns com.fulcrologic.guardrails-pro.test-fixtures.logging
  (:require
    #?@(:cljs [[goog.string :refer [format]]
               [goog.string.format]])
    [clojure.string :as str]
    [io.aviso.exception :as exc]
    [taoensso.timbre :as log]))

(defn compact-ns [s]
  (str/join "."
    (let [segments (str/split s #"\.")]
      (conj
        (mapv #(str/join "-" (mapv first (str/split % #"\-")))
          (butlast segments))
        (last segments)))))

(defn level->color-str [LEVEL]
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

(defn test-output-fn [{:keys [level ?err _vargs msg_
                              ?ns-str ?file timestamp_ ?line]}]
  (str (log/color-str :black (force timestamp_)) " "
    (level->color-str (str/upper-case (name level)))  " "
    (log/color-str :blue "[" (or (format "%20s" (compact-ns ?ns-str)) ?file "?") ":" (or (format "%3s" ?line) "?") "] ")
    (force msg_)
    (when-let [err ?err]
      (binding [exc/*default-frame-filter* test-exception-frame-filter]
        (str "\n" (log/stacktrace err {}))))))

(defn test-appender [stack]
  {:enabled? true
   :fn (fn [data]
         (-> data
           (select-keys [:level :?ns-str :?line :?err :vargs])
           (assoc :msg (force (:msg_ data)))
           (->> (swap! stack conj))))})
