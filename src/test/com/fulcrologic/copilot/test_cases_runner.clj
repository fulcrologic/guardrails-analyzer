(ns com.fulcrologic.copilot.test-cases-runner
  (:require
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.test :as t]
    [com.fulcrologic.copilot.analysis.analyzer :refer [defanalyzer]]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.copilot.checker :as cp.checker]
    [com.fulcrologic.copilot.reader :as cp.reader]
    [com.fulcrologic.copilot.test-fixtures :as tf]
    [com.fulcrologicpro.taoensso.encore :as enc]
    [com.fulcrologicpro.taoensso.timbre :as log]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification]]
    [clojure.string :as str]))

(defn test-case-keyword? [x]
  (and (keyword? x) (#{"problem" "binding"} (namespace x))))

(defn parse-test-cases [s]
  (when-let [?cases (and (not (re-find #";.*;" s))
                      (second (re-find #";\s*(:.*)$" s)))]
    (let [cases (read-string (str \[ ?cases \]))]
      (when-not (every? test-case-keyword? cases)
        (throw (ex-info "Test cases should all be keywords with 'problem' or 'binding' as a namespace"
                 {:cases cases :string s})))
      cases)))

(defn find-test-cases [file]
  (keep #(when-let [cases (parse-test-cases (second %))]
           {:line (first %) :cases cases})
    (map vector
      (map inc (range))
      (line-seq (io/reader file)))))

(defn read-test-case [file tests]
  (let [file-info (cp.reader/read-file file :clj)]
    (-> file-info
      (assoc :file-length (count (line-seq (io/reader file))))
      (assoc :file (str file))
      (assoc :tests tests)
      (update :forms drop-last))))

(defn subset-of? [expected actual]
  (set/subset? (set expected) (set actual)))

(defn check-test-case! [report! {:keys [message expected]} x]
  (t/testing (str "TEST CASE: " message)
    (cond
      (nil? expected) nil
      (map? expected)
      (when-not (subset-of? expected x)
        (report!
          {:type     :fail
           :actual   x
           :expected expected}))
      (_/checker? expected)
      (doseq [f ((_/all* expected) x)]
        (report!
          (merge {:type :fail} f)))
      :else
      (report!
        {:type     :error
         :actual   expected
         :expected `(some-fn map? _/checker?)}))))

(defn zip-fully [& colls]
  (take-while #(some some? %)
    (apply map vector
      (map #(concat % (repeat nil)) colls))))

(defn test-plan [{:keys [tests file-length problems bindings]} test-cases]
  (let [test-cases-by-line (group-by :line test-cases)
        bindings-by-line   (enc/map-vals #(sort-by ::cp.art/column-start %)
                             (group-by ::cp.art/line-start bindings))
        problems-by-line   (enc/map-vals #(sort-by ::cp.art/column-start %)
                             (group-by ::cp.art/line-start problems))]
    (reduce (fn [status-by-line line]
              (let [problems-on-line (get problems-by-line line)
                    bindings-on-line (get bindings-by-line line)
                    cases            (mapcat :cases (get test-cases-by-line line))
                    problem-cases    (->> cases
                                       (filter #(= "problem" (namespace %)))
                                       (map #(assoc (get tests %) :name %)))
                    binding-cases    (->> cases
                                       (filter #(= "binding" (namespace %)))
                                       (map #(assoc (get tests %) :name %)))]
                (cond-> status-by-line
                  (seq problems-on-line)
                  #_=> (assoc-in [line :problems] problems-on-line)
                  (seq bindings-on-line)
                  #_=> (assoc-in [line :bindings] bindings-on-line)
                  (seq problem-cases)
                  #_=> (assoc-in [line :problem-cases] problem-cases)
                  (seq binding-cases)
                  #_=> (assoc-in [line :binding-cases] binding-cases))))
      {} (range 1 (inc file-length)))))

(defn run-test-cases!
  [{:as env :keys [report! tc-file]} {:as tc-info :keys [tests]}]
  (let [test-cases (find-test-cases tc-file)]
    (doseq [[line {:as tp :keys [problem-cases binding-cases problems bindings]}]
            (sort-by key (test-plan tc-info test-cases))]
      (if (and (empty? problem-cases) (empty? problems)) nil
        (doseq [[c p] (zip-fully problem-cases problems)]
          (cond
            (not c) (report! {:type    :fail
                              :message (str "found an extra problem on line: " line)
                              :actual  p})
            (not p) (report! {:type     :fail
                              :message  (str "found an extra problem test case on line: " line)
                              :actual   nil
                              :expected c})
            :else (check-test-case! report! c p))))
      (if (and (empty? binding-cases) (empty? bindings)) nil
        (doseq [[c b] (zip-fully binding-cases bindings)]
          (if (not b)
            (report! {:type     :fail
                      :message  (str "found an extra binding test case on line: " line)
                      :actual   c
                      :expected nil})
            (check-test-case! report! c b)))))
    (when-let [non-existant-test-cases
               (seq (set/difference
                      (set (mapcat :cases test-cases))
                      (set (keys tests))))]
      (report! {:type     :error
                :actual   non-existant-test-cases
                :expected (set (keys tests))
                :message  (str "Found test cases that do not exist in <" tc-file "> !")}))
    (when-let [unused-test-cases
               (seq (set/difference
                      (set (keys tests))
                      (set (mapcat :cases test-cases))))]
      (report! {:type    :error
                :actual  (into {} (map #(vector % (get tests %)) unused-test-cases))
                :message (str "Found unused test cases in <" tc-file "> !")}))))

(defn test-file! [tc-file tests]
  (t/is (= true true))
  (log/with-merged-config tf/default-logging-config
    (let [tc-info (read-test-case tc-file tests)]
      (cp.art/clear-problems!)
      (cp.art/clear-bindings!)
      (require (symbol (:NS tc-info)) :reload)
      (try
        (cp.checker/check! tc-info
          #(run-test-cases! {:tc-file tc-file :report! t/do-report}
             (assoc tc-info
               :problems @cp.art/problems
               :bindings @cp.art/bindings)))
        (catch Exception e
          (log/error e "Unexpected error running test-case <" tc-file ">:")
          (t/do-report {:type :error :actual e}))))))

(defn tests-for [tc-file]
  (let [{:keys [NS]} (cp.reader/read-file tc-file :clj)]
    (require (symbol NS))
    (let [test-case-datas
          (map var-get
            (filter #(:test-case-data (meta %))
              (vals (ns-interns (symbol NS)))))]
      (assert (= 1 (count test-case-datas))
        "There can only be one deftc in a test case file")
      (first test-case-datas))))

;; LANDMARK: PUBLIC API BELOW

(defn run-tc-file! [report-fn tc-file {:keys [problems bindings]}]
  (try (run-test-cases! {:tc-file tc-file :report! report-fn}
    (assoc (read-test-case tc-file
             (tests-for tc-file))
      :problems problems
      :bindings bindings))
    (catch Exception e
      (report-fn {:type :error :actual e}))))

(defmacro deftc [& args]
  (let [suffix (last (str/split *file* #"[.]"))
        f      (str
                 (-> (str *ns*)
                   (str/replace #"[.]" "/")
                   (str/replace #"[-]" "_"))
                 "." suffix)]
    `(let [tc# ~(last args)]
       (def ~(with-meta (gensym) {:test-case-data true}) tc#)
       (specification ~(str "running assertions for: " f)
         :test-case ~@(butlast args)
         (test-file! (io/resource ~f) tc#)))))

(defanalyzer com.fulcrologic.copilot.test-cases-runner/deftc [env sexpr] {})
