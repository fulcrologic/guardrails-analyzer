(ns com.fulcrologic.copilot.logging
  (:require
    [clojure.java.io :as io]
    [com.fulcrologicpro.taoensso.timbre :as log])
  (:import
    (java.text SimpleDateFormat)))

(defn appender [path-format]
  (let [timestamp (.format (new SimpleDateFormat "yyyy-MM-dd_HH:mm")
                    (new java.util.Date))
        log-file (io/file (format path-format timestamp))]
    (log/info "Logging to file:" log-file)
    {:enabled?   true
     :async?     false
     :min-level  nil
     :rate-limit nil
     :output-fn  :inherit
     :fn
     (let [lock (new Object)]
       (fn [data]
         (let [{:keys [output_]} data
               output-str (str (force output_) "\n")]
           (try
             (locking lock
               (when-not (.exists log-file)
                 (io/make-parents log-file)))
             (spit log-file output-str :append true)
             (catch java.io.IOException _)))))}))

(defn add-appender! [& args]
  (log/merge-config! {:appenders {::appender (apply appender args)}}))
