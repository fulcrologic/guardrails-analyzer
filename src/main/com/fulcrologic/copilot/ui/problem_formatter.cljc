(ns com.fulcrologic.copilot.ui.problem-formatter
  (:require
    #?@(:cljs [[goog.string :refer [format]]
               [goog.string.format]])
    [clojure.string :as str]
    [com.fulcrologic.copilot.artifacts :as grp.art]
    [com.fulcrologicpro.com.rpl.specter :as $]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defn html-escape [s]
  (-> s
    (str/replace "&" "&amp;")
    (str/replace "<" "&lt;")
    (str/replace ">" "&gt;")))

(defmulti format-problem-mm (fn [problem params] (::grp.art/problem-type problem)))

(defn format-problem [problem]
  (let [message       (or (try (format-problem-mm problem (::grp.art/message-params problem))
                               (catch #?(:clj Throwable :cljs :default) e
                                 (log/error e "Failed to create message for problem:" problem)
                                 nil))
                        (format "Failed to create message for problem-type: <%s>!"
                          (::grp.art/problem-type problem)))
        plain-message (if (string? message) message (:message message))
        tooltip       (if (string? message) (html-escape message) (:tooltip message))]
    (-> problem
      (assoc ::grp.art/message plain-message)
      (assoc ::grp.art/tooltip tooltip))))

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

(defn format-arglist [{:as problem ::grp.art/keys [actual]}]
  (let [{::grp.art/keys [failing-samples]} actual]
    (case (count failing-samples)
      0 (do (log/error "Failed to get failing-samples from problem:" problem)
            "???")
      (str/join ", " (first failing-samples)))))

(defn format-expected [{::grp.art/keys [expected]}]
  (let [{::grp.art/keys [spec type]} expected]
    (or type spec)))

(defn format-expr [problem]
  (::grp.art/original-expression problem))

(defmethod format-problem-mm :error/value-failed-spec
  [problem params]
  {:message (format "The expression %s does not conform to the declared spec %s."
              (format-actual problem)
              (format-expected problem))
   :tooltip (format "The expression: <b>%s</b><br/>does not conform to the declared spec <b>%s</b>."
              (html-escape (format-actual problem))
              (html-escape (format-expected problem)))})

(defmethod format-problem-mm :error/bad-return-value
  [problem params]
  {:message (format "The Return spec is %s, but it is possible to return a value like %s."
              (format-expected problem)
              (format-actual problem))
   :tooltip (format "The function's return type is declared <b>%s</b><br/>It is possible for it to return a value like <b>%s</b>"
              (html-escape (format-expected problem))
              (html-escape (format-actual problem)))})

(defmethod format-problem-mm :error/sample-generator-failed
  [problem params]
  (format "%s could not be used to generate samples in a reasonable amount of time. Consider adding `with-gen` to your spec."
    (format-expr problem)))

(defmethod format-problem-mm :error/function-argument-failed-spec
  [problem params]
  {:message (format "The function argument: %s should be %s, but could end up having value like %s"
              (::grp.art/original-expression problem)
              (format-expected problem)
              (format-actual problem))
   :tooltip (format "The function argument: <b>%s</b><br/>with spec: <b>%s</b><br/>could contain an invalid value like <b>%s</b>."
              (::grp.art/original-expression problem)
              (html-escape (format-expected problem))
              (html-escape (format-actual problem)))})

(defmethod format-problem-mm :error/function-arguments-failed-predicate
  [problem params]
  {:message (format "The arguments: -> %s <- failed to conform to the function's argument predicate."
              (format-arglist problem))
   :tooltip (format "The arguments: <b>%s</b><br/>Failed to conform to the function's argument predicate."
              (html-escape (format-arglist problem)))})

(defmethod format-problem-mm :error/function-arguments-failed-spec
  [problem params]
  {:message (format "The varargs portion of the argument list (%s) does not conform to the expectation of %s."
              (format-arglist problem)
              (format-expected problem))
   :tooltip (format "The varargs portion of the argument list is <b>%s</b><br/>It not conform to the expectation of <b>%s</b>."
              (html-escape (format-arglist problem))
              (html-escape (format-expected problem)))})

(defmethod format-problem-mm :error/invalid-partially-applied-arguments
  [problem params]
  {:message (format "The partial application of the arguments %s to function %s violates one or more of that functions expected argument types."
              (format-expr problem)
              (:function params))
   :tooltip (format "The partial application of the arguments <b>%s</b> to <b>%s</b> violates one or more of that functions expected argument types."
              (html-escape (format-expr problem))
              (html-escape (:function params)))})

(defmethod format-problem-mm :error/expected-seqable-collection
  [problem params]
  (format "Expected a sequence, found <%s>."
    (format-expr problem)))

(defmethod format-problem-mm :error/invalid-function-arguments-count
  [problem params]
  (format "Incorrect arity"))

(defmethod format-problem-mm :warning/if-condition-never-reaches-then-branch
  [problem params]
  (format "The then branch is potentially unreachable. The samples of expression %s never yielded a truthy value."
    (format-expr problem)))

(defmethod format-problem-mm :warning/if-condition-never-reaches-else-branch
  [problem params]
  (format "The else branch is potentially unreachable. The samples of expression %s never yielded a false or nil value."
    (format-expr problem)))

(defmethod format-problem-mm :warning/failed-to-find-keyword-in-hashmap-samples
  [problem params]
  (format "Failed to find value for keyword <%s> in samples."
    (format-expr problem)))

(defmethod format-problem-mm :warning/qualified-keyword-missing-spec
  [problem params]
  (format "The qualified keyword %s has no spec. Possible typo?"
    (format-expr problem)))

(defmethod format-problem-mm :warning/get-in-might-never-succeed
  [problem params]
  (format "Call to get-in might fail to get <%s>. Try checking your specs & generators."
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
