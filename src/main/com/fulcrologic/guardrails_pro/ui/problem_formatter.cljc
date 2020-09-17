(ns com.fulcrologic.guardrails-pro.ui.problem-formatter
  (:require
    #?@(:cljs [[goog.string :refer [format]]
               [goog.string.format]])
    [clojure.string :as str]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.rpl.specter :as sp]
    [taoensso.timbre :as log]))

(defmulti format-problem-mm (fn [problem params] (::grp.art/problem-type problem)))

(defn format-problem [problem params]
  (assoc problem ::grp.art/message
    (or (try (format-problem-mm problem (::grp.art/message-params problem))
          (catch #?(:clj Throwable :cljs :default) e
            (log/error e "Failed to create message for problem:" problem)
            nil))
      (format "Failed to create message for problem-type %s!"
        (::grp.art/problem-type problem)))))

(defn format-problems [problems]
  (sp/transform (sp/walker ::grp.art/problem-type)
    format-problem problems))

(defmethod format-problem-mm :default [problem params]
  (str (::grp.art/problem-type problem)))

(defn format-actual [{{::grp.art/keys [failing-samples]} ::grp.art/actual}]
  (case (count failing-samples)
    0 "???"
    1 (first failing-samples)
    (str/join ", " failing-samples)))

(defn format-expected [{{::grp.art/keys [spec type]} ::grp.art/expected}]
  (or type spec))

(defn format-expr [problem]
  (::grp.art/original-expression problem))

(defmethod format-problem-mm :error/value-failed-spec
  [problem params]
  (format "Value <%s> failed spec <%s>"
    (format-actual problem)
    (format-expected problem)))

(defmethod format-problem-mm :error/sample-generator-failed
  [problem params]
  (format "Failed to generate samples for <%s>"
    (format-expr problem)))

(defmethod format-problem-mm :error/function-argument-failed-spec
  [problem params]
  (format "Function argument <%s> failed spec <%s>"
    (format-actual problem)
    (format-expected problem)))

(defmethod format-problem-mm :error/function-arguments-failed-predicate
  [problem params]
  (format "In call to <%s> arguments %s failed predicate with values <%s>"
    (:function-name params)
    (format-expr problem)
    (format-actual problem)))

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
