(ns com.fulcrologic.copilot.analytics
  #?(:cljs (:require-macros [com.fulcrologic.copilot.analytics]))
  (:require
    #?(:cljs [goog.object :as g.obj])
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defonce analyze-stats (atom []))

(defn record-analyze! [env sym args]
  (swap! analyze-stats conj
    {::symbol sym
     ::arity  (count args)}))

(defonce problem-stats (atom {}))

(defn record-problem! [env problem]
  (swap! problem-stats update
    (:com.fulcrologic.copilot.artifacts/problem-type problem)
    (fnil inc 0)))

(defonce usage-stats (atom {}))

(defn record-usage! [env]
  (swap! usage-stats update
    ::count (fnil inc 0)))

(defonce profiling-info (atom {}))

(defn record-profiling! [tag dt]
  (swap! profiling-info update tag (fnil conj []) dt))

(def now-nano*
  #?(:clj  #(System/nanoTime)
     :cljs (when-let [perf (g.obj/get js/window "performance")]
             (when-let [now-fn (g.obj/get perf "now")]
               #(* 1000000 (.call now-fn perf))))))

(defn now-nano [] (when now-nano* (now-nano*)))

(defmacro profile [tag body]
  `(let [before# (now-nano)
         return# ~body
         after#  (now-nano)]
     (when now-nano*
       (record-profiling! ~tag (- after# before#)))
     return#))

(defn get-customer-id [] "TODO")

(defn gather-analytics! []
  {::customer-id    (get-customer-id)
   ::analyze-stats  @analyze-stats
   ::problem-stats  @problem-stats
   ::profiling-info @profiling-info
   ::usage-stats    @usage-stats})

(defn clear-analytics! []
  (reset! analyze-stats [])
  (reset! problem-stats {})
  (reset! profiling-info {})
  (reset! usage-stats {}))

(def debug?
  #?(:clj (some? (System/getProperty "copilot.analytics.debug"))
     :cljs false))

(defn send-analytics! [analytics]
  (if debug?
    (log/debug "ANALYTICS:" analytics)
    ;; TODO FIXME should send to endpoint & on ack clear atoms
    #?(:clj #(spit "/tmp/copilot-analytics.txt" (pr-str %) :append true)
       :cljs tap>))
  (clear-analytics!))

(defonce last-report-time (atom nil))

(defn now-in-seconds []
  (/ #?(:cljs (js/Date.now)
        :clj  (System/currentTimeMillis))
    1000))

;; CONTEXT: Runs after each check command
(defn report-analytics! []
  (let [now-s (now-in-seconds)
        last-s @last-report-time]
    (when (or debug? (nil? last-s)
            (< (* 5 60) (- now-s last-s) ))
      (let [analytics (gather-analytics!)]
        (reset! last-report-time now-s)
        (send-analytics! analytics)))))
