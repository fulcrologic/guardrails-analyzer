(ns com.fulcrologic.copilot.test-fixtures.logging
  (:require
    [clojure.string :as str]))

(defn compact-ns [s]
  (str/join "."
    (let [segments (str/split s #"\.")]
      (conj
        (mapv #(str/join "-" (mapv first (str/split % #"\-")))
          (butlast segments))
        (last segments)))))
