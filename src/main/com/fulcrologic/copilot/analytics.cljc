(ns com.fulcrologic.copilot.analytics
  #?(:cljs (:require-macros [com.fulcrologic.copilot.analytics]))
  (:require
    #?(:cljs [goog.object :as g.obj])
    #?(:clj [com.fulcrologic.copilot.licensing :as cp.license])
    #?(:clj [org.httpkit.client :as http])
    [com.fulcrologic.copilot.config :as cp.cfg]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defonce config
  (cp.cfg/load-config!))

(def dev?
  #?(:clj (some? (System/getProperty "dev"))
     :cljs false))

(defn analytics? []
  (or dev? (:analytics? config false)))

(defonce analyze-stats (atom []))

(defn record-analyze! [env sym args]
  (when (analytics?)
    (swap! analyze-stats conj
      {:symbol sym
       :arity  (count args)})))

(defonce problem-stats (atom {}))

(defn record-problem! [env problem]
  (when (analytics?)
    (swap! problem-stats update
      (:com.fulcrologic.copilot.artifacts/problem-type problem)
      (fnil inc 0))))

(defonce usage-stats (atom {}))

(defn record-usage! [env forms-count]
  (when (analytics?)
    (swap! usage-stats
      update :number-of-top-level-forms-checked
      (fnil + 0) forms-count)))

(defonce profiling-info (atom {}))

(defn record-profiling! [tag dt]
  (when (analytics?)
    (swap! profiling-info update tag (fnil conj []) dt)))

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

(defn gather-analytics! []
  {:license/number #?(:clj (cp.license/get-license-number) :cljs nil)
   ::analyze-stats  @analyze-stats
   ::problem-stats  @problem-stats
   ::profiling-info @profiling-info
   ::usage-stats    @usage-stats})

(defn clear-analytics! []
  (reset! analyze-stats [])
  (reset! problem-stats {})
  (reset! profiling-info {})
  (reset! usage-stats {}))

(defn send-analytics! [analytics]
  (if dev?
    (log/debug "analytics:" analytics)
    #?(:cljs nil
       :clj (try
              (let [{:keys [status] :as resp}
                    @(http/post "https://fulcrologic.com/analytics"
                       {:multipart [{:name "password" :content "!!!uploadenzie"}
                                    {:name "number" :content (str (:license/number analytics))}
                                    {:name "file" :content (pr-str analytics) :filename "analytics"}]})]
                (if (= status 200)
                  (clear-analytics!)
                  (log/error "Failed to send analytics to server because:" resp)))
              (catch Exception e
                (log/error e "Failed to send analytics!"))))))

(defonce last-report-time (atom nil))

(defn now-in-seconds []
  (/ #?(:cljs (js/Date.now)
        :clj  (System/currentTimeMillis))
    1000))

(defonce report-interval (* 15 60))

;; CONTEXT: Runs after each check command
(defn report-analytics! []
  (try (when (analytics?)
         (let [now-s (now-in-seconds)
               last-s @last-report-time]
           (when (or dev? (nil? last-s)
                   (< report-interval (- now-s last-s) ))
             (let [analytics (gather-analytics!)]
               (reset! last-report-time now-s)
               (send-analytics! analytics)))))
    (catch #?(:clj Exception :cljs :default) e
      (log/error e "Failed to report analytics because:")
      nil)))
