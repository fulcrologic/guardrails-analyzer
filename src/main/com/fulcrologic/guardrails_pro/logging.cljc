(ns com.fulcrologic.guardrails-pro.logging
  #?(:cljs (:require-macros [com.fulcrologic.guardrails-pro.logging]))
  (:require [clojure.string :as str]))

(def level (atom :info))

(defn -log [args]
  #?(:clj
     (.println System/out (str/join " " (map str args)))
     :cljs
     (apply js/console.log args)))

(defn trace [& args]
  (when (#{:trace} @level)
    (-log args)))

(defn debug [& args]
  (when (#{:trace :debug} @level)
    (-log args)))

(defn info [& args]
  (when (#{:trace :debug :info} @level)
    (-log args)))

(defn warn [& args]
  (when (#{:trace :debug :info :warn} @level)
    (-log args)))

(defn error [& args]
  (-log args))

#?(:clj
   (defmacro spy
     ([v]
      (let [prefix (str v " => ")]
        `(do
           (info (str ~prefix ~v))
           ~v)))
     ([level v]
      (let [prefix (str v " => ")]
        `(do
           (info (str ~prefix ~v))
           ~v)))
     ([level label v]
      (let [prefix (str label " => ")]
        `(do
           (info (str ~prefix ~v))
           ~v)))))

(comment
  (info "label" 34))
