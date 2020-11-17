(ns com.fulcrologic.guardrails-pro.test-fixtures.logging
  (:require
    #?@(:cljs [[goog.string :refer [format]]
               [goog.string.format]])
    [clojure.string :as str]))

(defn compact-ns [s]
  (str/join "."
    (let [segments (str/split s #"\.")]
      (conj
        (mapv #(str/join "-" (mapv first (str/split % #"\-")))
          (butlast segments))
        (last segments)))))
