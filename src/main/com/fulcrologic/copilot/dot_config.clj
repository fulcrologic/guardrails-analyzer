Copyright (c) Fulcrologic, LLC. All rights reserved.

Permission to use this software requires that you
agree to our End-user License Agreement, legally obtain a license,
and use this software within the constraints of the terms specified
by said license.

You may NOT publish, redistribute, or reproduce this software or its source
code in any form (printed, electronic, or otherwise) except as explicitly
allowed by your license agreement..

(ns com.fulcrologic.copilot.dot-config
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io])
  (:import
    (java.io FileNotFoundException)
    (javax.swing JFrame JOptionPane)))

(def config-file
  (io/file (System/getProperty "user.home")
    ".copilot/config.edn"))

(def default-config {:logging/config {:min-level :info}})

(defn show-create-config-dialog []
  (let [options (to-array ["Yes" "No"])
        user-chose (JOptionPane/showOptionDialog
                     (doto (new JFrame)
                       (.setAlwaysOnTop true))
                     (str "Could not find config file at " config-file "."
                       "\nCopilot will create a default config for you.\n"
                       "\nWould you like to enable analytics to help us better improve Copilot?")
                     "Copilot: Enable Analytics?"
                     JOptionPane/DEFAULT_OPTION JOptionPane/QUESTION_MESSAGE
                     nil options nil)
        config (merge default-config
                 (case (get options user-chose false)
                   "Yes" {:analytics? true}
                   {}))]
    (io/make-parents config-file)
    (spit config-file (pr-str config))
    config))

(defn load-config! []
  (try
    (edn/read-string (slurp config-file))
    (catch FileNotFoundException _
      (show-create-config-dialog))
    (catch Exception _ {})))
