(ns timesheets
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer :all]
    [clojure.edn :as edn])
  ;(:gen-class)
  (:import (java.io File)))

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

(defn -main [& args]
  (doseq [^String f args]
    (let [file (File. f)]
      (if (.exists file)
        (let [file-content (-> file io/reader slurp)
              entries      (edn/read-string file-content)
              problems     (seq (re-seq #"\D0\d\d\d\D" file-content))]
          (if problems
            (doseq [p problems]
              (println "Octal value in time spans " p))
            (->> (reduce
                   (fn [total {:keys [date description timespans]}]
                     (println (format "%-11.11s %-30.30s %6.2f  %s" date description (sum-timespans timespans) timespans))
                     (+ total (sum-timespans timespans)))
                   0
                   entries)
              (format "\nTotal: %42.2f")
              (println))))
        (println "Cannot read " f)))))

(comment
  (-main "timesheets/tony.edn"))
