(ns com.fulcrologic.copilot.test-cases-runner
  (:require
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :as t]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.checker :as cp.checker]
    [com.fulcrologic.copilot.daemon.reader :as reader]
    [com.fulcrologicpro.taoensso.encore :as enc]))

(defn get-test-cases []
  (->> (io/file "src/test_cases")
    (file-seq)
    (filter #(.isFile %))))

(defn parse-test-cases [s]
  (when-let [cases (second (re-find #";\s*assert:(.*)$" s))]
    (map (comp keyword str/trim)
      (str/split cases #","))))

(defn find-test-cases [file]
  (keep #(when-let [cases (parse-test-cases (second %))]
           {:line (first %) :cases cases})
    (map vector
      (map inc (range))
      (line-seq (io/reader file)))))

(defn subset-of? [expected actual]
  (set/subset? (set expected) (set actual)))

(defn read-test-case [file]
  (let [msg (reader/read-file file {:checker-type :test-case})]
    (-> msg
      (assoc :file-length (count (line-seq (io/reader file))))
      (assoc :file (str file))
      (assoc :tests (last (:forms msg)))
      (update :forms drop-last))))

(defn check-test-case! [{:keys [message expected]} p]
  (t/testing message
    (when-not (subset-of? expected p)
      (t/do-report
        {:type :fail
         :actual p
         :expected expected}))))

(defn check-test-cases! []
  (t/is (= true true))
  (cp.art/clear-problems!)
  (doseq [tc-file (get-test-cases)]
    (t/testing (str "TESTING TEST CASE FILE: " tc-file "\n")
      (let [{:as msg :keys [NS tests file-length]} (read-test-case tc-file)]
        (require (symbol NS) :reload)
        (cp.checker/check! msg
          (fn []
            (let [test-cases (find-test-cases tc-file)]
              (when-let [non-existant-test-cases
                         (seq (set/difference
                                (set (mapcat :cases test-cases))
                                (set (keys tests))))]
                (t/do-report {:type :error
                              :actual non-existant-test-cases
                              :expected (set (keys tests))
                              :message (str "Found test cases that do not exist in <" tc-file "> !")}))
              (let [problems @cp.art/problems
                    test-cases-by-line (group-by :line test-cases)
                    problems-by-line (enc/map-vals #(sort-by ::cp.art/column-start %)
                                       (group-by ::cp.art/line-start problems))
                    asdf (reduce (fn [status-by-line line]
                                   (let [problems-on-line (get problems-by-line line)
                                         cases (map #(get tests %)
                                                 (mapcat :cases (get test-cases-by-line line)))]
                                     (cond-> status-by-line
                                       (seq problems-on-line)
                                       #_=> (assoc-in [line :problems] problems-on-line)
                                       (seq cases)
                                       #_=> (assoc-in [line :cases] cases))))
                           {} (range 1 (inc file-length)))]
                (doseq [{:keys [problems cases]} (vals asdf)]
                  (cond
                    (and (empty? problems) (empty? cases)) nil
                    (empty? problems)
                    (t/do-report {:type :fail
                                  :message "found no problems"
                                  :actual []
                                  :expected cases})
                    (empty? cases)
                    (t/do-report {:type :fail
                                  :message "found extra unchecked problems"
                                  :actual problems
                                  :expected []})
                    :else
                    (doseq [[c p] (take-while #(not= % [nil nil])
                                    (map vector
                                      (concat cases (repeat nil))
                                      (concat problems (repeat nil))))]
                      (cond
                        (not c) (t/do-report {:type :fail
                                              :message "found an extra problem"
                                              :actual p})
                        (not p) (t/do-report {:type :fail
                                              :message "found an extra test case"
                                              :actual c
                                              :expected nil})
                        :else (check-test-case! c p))))))
              (when-let [unused-test-cases
                         (seq (set/difference
                                (set (keys tests))
                                (set (mapcat :cases test-cases))))]
                (t/do-report {:type :error
                              :actual (into {} (map #(vector % (get tests %)) unused-test-cases))
                              :message (str "Found unused test cases in <" tc-file "> !")})))))))))
