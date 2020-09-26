(ns timesheets
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest are is]]
    [clojure.tools.cli :refer [parse-opts]]
    [clojure.edn :as edn])
  (:gen-class)
  (:import
    (java.io File)))

(defn military->decimal
  "Convert a time in military form to a decimal number."
  [tm]
  (let [hrs  (int (/ tm 100))
        mins (mod tm 100)]
    (+ hrs (/ mins 60.0))))

(deftest m->d-test
  (are [tm expected]
    (is (= expected (military->decimal tm)))
    100 1.0
    115 1.25
    830 8.5
    1545 15.75
    0 0.0))

(defn tmlength
  "Compute the time interval for two military times (e.g. 2130), assuming the time from a to b is contiguous.
  This means that if b looks smaller than a, we add 24 to it before calculating the difference."
  [a b]
  (let [diff (- (military->decimal b) (military->decimal a))]
    (if (pos? diff) diff (+ diff 24))))

(deftest tmlength-test
  (are [a b expected]
    (is (= expected (tmlength a b)))
    0 200 2.0
    2300 200 3.0
    2100 600 9.0
    2100 0 3.0
    2100 330 6.5
    800 2315 15.25))

(defn sum-timespans [timespans]
  (reduce
    (fn [result span]
      (let [[start end] span]
        (+ result (tmlength start end))))
    0
    timespans))

(defn validate-timespans! [{:keys [date timespans]}]
  (when-let [invalid-timespans (seq (filter
                                      (some-fn
                                        (comp not vector?)
                                        #(not= 2 (count %)))
                                      timespans))]
    (throw (ex-info (format "Hours entry for date: %s has invalid timespans: %s"
                      date invalid-timespans)
             {}))))

(defn group-by-day [entries]
  (mapv (fn [[date values]]
          {:date date
           :description (str/join " + " (map :description values))
           :timespans (vec (mapcat :timespans values))})
    (sort-by (comp #(str/split % #"\-") first)
      (group-by :date entries))))

(defn hours-for-files [files {:keys [by-day]}]
  (doseq [^String f files]
    (let [file (File. f)]
      (if-not (.exists file)
        (throw (ex-info (str "Cannot read " f) {}))
        (let [file-content (-> file io/reader slurp)
              entries      (edn/read-string file-content)
              entries      (cond-> entries by-day (group-by-day))
              octal-values (seq (re-seq #"\D0\d\d\d\D" file-content))]
          (when octal-values
            (throw (ex-info (str "Octal value in time spans: " octal-values) {})))
          (let [output (atom [])
                total  (reduce
                         (fn [total {:as entry :keys [date description timespans]}]
                           (validate-timespans! entry)
                           (let [sum (sum-timespans timespans)]
                             (swap! output conj
                               (format "%-11.11s %-30.30s %6.2f  %s"
                                 date description sum timespans))
                             (+ total sum)))
                         0
                         entries)]
            (doseq [out @output] (println out))
            (println (format "\nTotal: %42.2f" total))))))))

(def cli-options
  [[nil "--by-day" "Group By Day"
    :default false]])

(defn -main [& args]
  (try
    (let [{:keys [arguments options]} (parse-opts args cli-options)]
      (hours-for-files arguments options))
    (catch Throwable t
      (.println System/err
        (if (= (type t) clojure.lang.ExceptionInfo)
          (.getMessage t)
          (str t)))
      (System/exit 1))))

(comment
  (-main "timesheets/tony.edn"))
