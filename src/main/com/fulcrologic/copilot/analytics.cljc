;; Copyright (c) Fulcrologic, LLC. All rights reserved.
;;
;; Permission to use this software requires that you
;; agree to our End-user License Agreement, legally obtain a license,
;; and use this software within the constraints of the terms specified
;; by said license.
;;
;; You may NOT publish, redistribute, or reproduce this software or its source
;; code in any form (printed, electronic, or otherwise) except as explicitly
;; allowed by your license agreement..

(ns com.fulcrologic.copilot.analytics
  #?(:cljs (:require-macros [com.fulcrologic.copilot.analytics]))
  (:require
    #?(:cljs [goog.object :as g.obj])
    #?(:clj [com.fulcrologic.copilot.licensing :as cp.license])
    [com.fulcrologicpro.taoensso.timbre :as log]))

(def dev?
  #?(:clj  (some? (System/getProperty "dev"))
     :cljs false))

(defonce analyze-stats (atom []))

(defn record-analyze! [env sym args]
  (swap! analyze-stats conj
    {:symbol sym
     :arity  (count args)}))

(defonce problem-stats (atom {}))

(defn record-problem! [env problem]
  (swap! problem-stats update
    (:com.fulcrologic.copilot.artifacts/problem-type problem)
    (fnil inc 0)))

(defonce usage-stats (atom {}))

(defn record-usage! [env {:keys [forms check-command-type]}]
  (let [forms-count (count forms)]
    (swap! usage-stats
      #(-> %
         (update :number-of-top-level-forms-checked
           (fnil + 0) forms-count)
         (update-in [:usage-by-check-command check-command-type]
           (fnil inc 0))))))

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
     (when (and before# after#)
       (record-profiling! ~tag (- after# before#)))
     return#))

(defn clear-analytics! []
  (reset! analyze-stats [])
  (reset! problem-stats {})
  (reset! profiling-info {})
  (reset! usage-stats {}))

(defonce last-report-time (atom nil))

(defn now-in-seconds []
  (/ #?(:cljs (js/Date.now)
        :clj  (System/currentTimeMillis))
    1000))

(defonce report-interval (* 15 60))

;; CONTEXT: Runs after each check command
(defn gather-analytics! []
  (try (let [now-s  (now-in-seconds)
             last-s @last-report-time]
         (when (or dev? (nil? last-s)
                 (< report-interval (- now-s last-s)))
           (let [analytics {:license/number  #?(:clj (cp.license/get-license-number) :cljs nil)
                            ::analyze-stats  @analyze-stats
                            ::problem-stats  @problem-stats
                            ::profiling-info @profiling-info
                            ::usage-stats    @usage-stats}]
             (reset! last-report-time now-s)
             analytics)))
       (catch #?(:clj Exception :cljs :default) e
         (log/error e "Failed to report analytics because:")
         nil)))
