(ns com.fulcrologic.guardrails-pro.test-checkers
  (:require
    [fulcro-spec.check :refer [checker]]))

(defn of-length?* [exp-len]
  (checker [actual]
    (let [length (count actual)]
      (when-not (= exp-len length)
        {:actual actual
         :expected `(~'of-length?* ~exp-len)
         :message (format "Expected count to be %d was %d" exp-len length)}))))
