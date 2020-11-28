(ns com.fulcrologic.copilot.stateful.rose-tree
  (:require [clojure.test.check.rose-tree :as rose]))

(defn- make-bound-head []
  #?(:cljs
     (fn [sq]
       (if (seq sq)
         (first sq)
         ::empty))
     :clj
     (bound-fn [sq]
       (if (seq sq)
         (first sq)
         ::empty))))

(defn- bound-lazy-seq [sq]
  (let [head-of   (make-bound-head)
        bound-seq (fn bound-seq [sq]
                    (lazy-seq
                      (let [head (head-of sq)]
                        (when (not= head ::empty)
                          (cons head (bound-seq (rest sq)))))))]
    (bound-seq sq)))

(defn bound-tree
  "Ensure that all Rose tree children have access to the dynamic variables
   currently defined."
  [tree]
  (rose/make-rose (rose/root tree)
    (bound-lazy-seq
      (map bound-tree (rose/children tree)))))

(defn fn-tree
  "Create a rose-tree based on the given function."
  [value-fn]
  (rose/make-rose (value-fn)
    (repeatedly 200 (comp rose/pure value-fn))))
