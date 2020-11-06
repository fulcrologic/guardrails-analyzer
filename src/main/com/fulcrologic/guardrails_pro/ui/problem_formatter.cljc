(ns com.fulcrologic.guardrails-pro.ui.problem-formatter
  (:require
    #?@(:cljs [[goog.string :refer [format]]
               [goog.string.format]])
    [clojure.string :as str]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.rpl.specter :as $]
    [taoensso.timbre :as log]))

(defn html-escape [s]
  (-> s
    (str/replace "&" "&amp;")
    (str/replace "<" "&lt;")
    (str/replace ">" "&gt;")))

(defmulti format-problem-mm (fn [problem params] (::grp.art/problem-type problem)))

(defn format-problem [problem]
  (let [message (or (try (format-problem-mm problem (::grp.art/message-params problem))
                      (catch #?(:clj Throwable :cljs :default) e
                        (log/error e "Failed to create message for problem:" problem)
                        nil))
                  (format "Failed to create message for problem-type: <%s>!"
                    (::grp.art/problem-type problem)))]
    (-> problem
      (assoc ::grp.art/message message)
      (assoc ::grp.art/tooltip (html-escape message)))))

(defn format-problems [problems]
  ($/transform ($/walker ::grp.art/problem-type)
    format-problem problems))

(defmethod format-problem-mm :default [problem params]
  (str (::grp.art/problem-type problem)))

(defn format-actual [{:as problem ::grp.art/keys [actual]}]
  ;; TODO: test nil "" ...
  (let [{::grp.art/keys [failing-samples]} actual]
    (case (count failing-samples)
      0 (do (log/error "Failed to get failing-samples from problem:" problem)
          "???")
      1 (pr-str (first failing-samples))
      (str/join ", "
        (map pr-str failing-samples)))))

(defn format-expected [{::grp.art/keys [expected]}]
  (let [{::grp.art/keys [spec type]} expected]
    (or type spec)))

(defn format-expr [problem]
  (::grp.art/original-expression problem))

(defmethod format-problem-mm :error/value-failed-spec
  [problem params]
  (format "Value <%s> failed spec <%s>."
    (format-actual problem)
    (format-expected problem)))

(defmethod format-problem-mm :error/sample-generator-failed
  [problem params]
  (format "Failed to generate samples for <%s>."
    (format-expr problem)))

(defmethod format-problem-mm :error/function-argument-failed-spec
  [problem params]
  (format "Function argument <%s> failed spec <%s>."
    (format-actual problem)
    (format-expected problem)))

(defmethod format-problem-mm :error/function-arguments-failed-predicate
  [problem params]
  (format "In call to <%s> arguments %s failed predicate with values <%s>."
    (:function-name params)
    (format-expr problem)
    (format-actual problem)))

(defmethod format-problem-mm :error/function-arguments-failed-spec
  [problem params]
  (format "Function arguments <%s> failed spec <%s>."
    (format-actual problem)
    (format-expected problem)))

(defmethod format-problem-mm :error/invalid-function-arguments
  [problem params]
  (format "Invalid arguments <%s> to function <%s>."
    (format-expr problem)
    (:function params)))

(defmethod format-problem-mm :error/expected-seqable-collection
  [problem params]
  (format "Expected a sequence, found <%s>."
    (format-expr problem)))

(defmethod format-problem-mm :error/invalid-function-arguments-count
  [problem params]
  (format "inv args count %s"
    (pr-str problem)))

(defmethod format-problem-mm :warning/if-condition-never-reaches-then-branch
  [problem params]
  (format "If then branch potentially unreachable, condition <%s> was never sampled to be truthy."
    (format-expr problem)))

(defmethod format-problem-mm :warning/if-condition-never-reaches-else-branch
  [problem params]
  (format "If else branch potentially unreachable, condition <%s> was never sampled to be falsey."
    (format-expr problem)))

(defmethod format-problem-mm :warning/failed-to-find-keyword-in-hashmap-samples
  [problem params]
  (format "Failed to find value for keyword <%s> in samples."
    (format-expr problem)))

(defmethod format-problem-mm :warning/qualified-keyword-missing-spec
  [problem params]
  (format "Fully qualified keyword <%s> has no spec. Possible typo?"
    (format-expr problem)))

(defmethod format-problem-mm :warning/missing-samples
  [problem params]
  (format "Could not generate values for <%s>"
    (format-expr problem)))

(defmethod format-problem-mm :warning/unable-to-check
  [problem params]
  (format "Could not check <%s>"
    (format-expr problem)))

(defmethod format-problem-mm :info/failed-to-analyze-unknown-expression
  [problem params]
  "Failed to analyze expression!")
