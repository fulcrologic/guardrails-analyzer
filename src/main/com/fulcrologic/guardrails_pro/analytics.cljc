(ns com.fulcrologic.guardrails-pro.analytics
  #?(:cljs (:require-macros [com.fulcrologic.guardrails-pro.analytics]))
  (:require
    #?(:cljs [goog.object :as g.obj])
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]))

(defonce analyze-stats (atom []))

(defn record-analyze-stats!
  [{::grp.art/keys [current-ns checking-sym]} sexpr problems]
  (when (seq? sexpr)
    (swap! analyze-stats conj
      (cond-> {:analyzing (first sexpr)
               :arity     (count (rest sexpr))
               :problems  problems}
        (and current-ns checking-sym)
        (assoc :in-defn (hash (symbol current-ns (str checking-sym))))))))

(defmacro with-analytics [env sexpr condition body-fn]
  `(let [problems# (atom [])
         env#      (grp.art/with-problem-observer ~env
                     #(swap! problems# conj
                        (select-keys %
                          [::grp.art/problem-type])))
         result#   (~body-fn env#)]
     (when ~condition
       (record-analyze-stats! env# ~sexpr @problems#))
     result#))

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
  (let [info @profiling-info
        stats @analyze-stats
        cid (get-customer-id)]
    {:customer-id cid
     :profiling-info info
     :analyze-stats stats}))

(defonce last-report-time (atom nil))

(defn now-in-seconds []
  (/ #?(:cljs (js/Date.now)
        :clj  (System/currentTimeMillis))
    1000))

(defn report-analytics! []
  (let [now-s (now-in-seconds)
        last-s @last-report-time]
    (when (or (nil? last-s)
            (< (* 5 60) (- now-s last-s) ))
      (let [analytics (gather-analytics!)]
        (reset! last-report-time now-s)
        (reset! profiling-info {})
        (reset! analyze-stats [])
        ;; TODO FIXME should send to endpoint & on ack clear stats
        (#?(:clj #(spit "/tmp/grp-analytics.txt" (pr-str %) :append true)
            :cljs tap>)
          analytics)))))
