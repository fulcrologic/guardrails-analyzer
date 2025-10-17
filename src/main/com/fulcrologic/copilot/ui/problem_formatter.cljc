;; Copyright (c) Fulcrologic, LLC. All rights reserved.
;;
;; Permission to use this software requires that you
;; agree to our End-user License Agreement, legally obtain a license,
;; and use this software within the constraints of the terms specified
;; by said license.
;;
;; You may NOT publish, redistribute, or reproduce this software or its source
;; code in any form (printed, electronic, or otherwise) except as explicitly
;; allowed by your license agreement..

(ns com.fulcrologic.copilot.ui.problem-formatter
  (:require
    #?@(:cljs [[goog.string :refer [format]]
               [goog.string.format]])
    [clojure.string :as str]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.rpl.specter :as $]
    [com.fulcrologicpro.taoensso.timbre :as log]))

(defn html-escape [s]
  (-> s
    (str/replace "&" "&amp;")
    (str/replace "<" "&lt;")
    (str/replace ">" "&gt;")))

(defmulti format-problem-mm (fn [problem params] (::cp.art/problem-type problem)))

(defn format-problem [problem]
  (let [message       (or (try (format-problem-mm problem (::cp.art/message-params problem))
                               (catch #?(:clj Throwable :cljs :default) e
                                 (log/error e "Failed to create message for problem:" problem)
                                 nil))
                        (format "Failed to create message for problem-type: <%s>!"
                          (::cp.art/problem-type problem)))
        plain-message (if (string? message) message (:message message))
        tooltip       (if (string? message) (html-escape message) (:tooltip message))]
    (-> problem
      (assoc ::cp.art/message plain-message)
      (assoc ::cp.art/tooltip tooltip))))

(defn format-problems [problems]
  ($/transform ($/walker ::cp.art/problem-type)
    format-problem problems))

(defmethod format-problem-mm :default [problem params]
  (str (::cp.art/problem-type problem)))

(defn format-actual [{:as problem ::cp.art/keys [actual]}]
  ;; TODO: test nil "" ...
  (let [{::cp.art/keys [failing-samples]} actual]
    (case (count failing-samples)
      0 (do (log/error "Failed to get failing-samples from problem:" problem)
            "???")
      1 (pr-str (first failing-samples))
      (str/join ", "
        (map pr-str failing-samples)))))

(defn format-arglist [{:as problem ::cp.art/keys [actual]}]
  (let [{::cp.art/keys [failing-samples]} actual]
    (case (count failing-samples)
      0 (do (log/error "Failed to get failing-samples from problem:" problem)
            "???")
      (str/join ", " (first failing-samples)))))

(defn format-expected [{::cp.art/keys [expected]}]
  (let [{::cp.art/keys [spec type]} expected]
    (or type spec)))

(defn format-expr [problem]
  (pr-str (::cp.art/original-expression problem)))

(defn strip-meta-wrappers
  "Recursively strip meta-wrapper maps from an expression"
  [expr]
  (cond
    ;; If it's a meta-wrapper, extract the value and recurse
    (and (map? expr) (:com.fulcrologic.copilot/meta-wrapper? expr))
    (strip-meta-wrappers (:value expr))

    ;; If it's a list, recursively process each element
    (list? expr)
    (apply list (map strip-meta-wrappers expr))

    ;; If it's a vector, recursively process each element
    (vector? expr)
    (vec (map strip-meta-wrappers expr))

    ;; If it's a map (but not a meta-wrapper), recursively process values
    (map? expr)
    (into {} (map (fn [[k v]] [k (strip-meta-wrappers v)]) expr))

    ;; Otherwise, return as-is
    :else
    expr))

(defn format-condition-expression [expr]
  "Format a condition expression, stripping meta-wrappers recursively"
  (pr-str (strip-meta-wrappers expr)))

(defn format-path-condition [condition]
  "Format a single path condition into a readable string"
  (let [{::cp.art/keys [condition-expression condition-location branch determined? condition-value]} condition
        cond-str   (format-condition-expression condition-expression)
        line-num   (::cp.art/line-start condition-location)
        branch-str (case branch
                     :then "then"
                     :else "else"
                     (str branch))]
    (if determined?
      (format "%s → %s (line %s)" cond-str branch-str line-num)
      (format "%s → %s (line %s, indeterminate)" cond-str branch-str line-num))))

(defn format-path [path]
  "Format an execution path's conditions"
  (let [{::cp.art/keys [conditions]} path]
    (str/join " AND " (map format-path-condition conditions))))

(defn format-failing-paths [failing-paths]
  "Format multiple failing paths for display"
  (case (count failing-paths)
    0 nil
    1 (str " when " (format-path (first failing-paths)))
    (str " on " (count failing-paths) " paths:\n  • "
      (str/join "\n  • " (map format-path failing-paths)))))

(defmethod format-problem-mm :error/value-failed-spec
  [problem params]
  {:message (format "The expression %s does not conform to the declared spec %s."
              (format-actual problem)
              (format-expected problem))
   :tooltip (format "The expression <b>%s</b> could potentially have the value <b>%s</b>, which does not conform to the declared spec of <b>%s</b>."
              (html-escape (format-expr problem))
              (html-escape (format-actual problem))
              (html-escape (format-expected problem)))})

(defmethod format-problem-mm :error/bad-return-value
  [problem params]
  (let [failing-paths  (get-in problem [::cp.art/actual ::cp.art/failing-paths])
        path-context   (when (seq failing-paths) (format-failing-paths failing-paths))
        message-suffix (or path-context ".")]
    {:message (format "The Return spec is %s, but it is possible to return a value like %s%s"
                (format-expected problem)
                (format-actual problem)
                message-suffix)
     :tooltip (format "The function's return spec is declared <b>%s</b><br/>It is possible for it to return a value like <b>%s</b>%s"
                (html-escape (format-expected problem))
                (html-escape (format-actual problem))
                (if path-context
                  (str "<br/>" (html-escape path-context))
                  ""))}))

(defmethod format-problem-mm :error/sample-generator-failed
  [problem params]
  (format "%s could not be used to generate samples in a reasonable amount of time. Consider adding `with-gen` to your spec."
    (format-expr problem)))

(defmethod format-problem-mm :error/function-argument-failed-spec
  [problem params]
  {:message (format "The function argument: %s should be %s, but could end up having value like %s"
              (format-expr problem)
              (format-expected problem)
              (format-actual problem))
   :tooltip (format "The function argument: <b>%s</b><br/>with spec: <b>%s</b><br/>could contain an invalid value like <b>%s</b>."
              (html-escape (format-expr problem))
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
  {:message (format "The partial application of the arguments %s to function %s violates one or more of that functions expected argument specs."
              (format-expr problem)
              (:function params))
   :tooltip (format "The partial application of the arguments <b>%s</b> to <b>%s</b> violates one or more of that functions expected argument specs."
              (html-escape (format-expr problem))
              (html-escape (:function params)))})

(defmethod format-problem-mm :error/expected-seqable-collection
  [problem params]
  (format "Expected a sequence, found <%s>."
    (format-expr problem)))

(defmethod format-problem-mm :error/invalid-function-arguments-count
  [problem params]
  (format "Incorrect arity"))

(defmethod format-problem-mm :warning/not-implemented
  [problem params]
  (format "Expression <%s> is not currently implemented yet."
    (format-expr problem)))

(defmethod format-problem-mm :warning/if-condition-never-reaches-then-branch
  [problem params]
  (format "The then branch is potentially unreachable. The samples of expression %s never yielded a truthy value."
    (format-expr problem)))

(defmethod format-problem-mm :warning/if-condition-never-reaches-else-branch
  [problem params]
  (format "The else branch is potentially unreachable. The samples of expression %s never yielded a false or nil value."
    (format-expr problem)))

(defmethod format-problem-mm :warning/destructured-map-entry-may-not-be-present
  [problem params]
  {:message (format "The destructured symbol `%s` may not exist in the source map."
              (format-expr problem))
   :tooltip (format "The destructured symbol <code>%s</code> may not be present in the source map. This may indicate a
   typo in your destructuring or in the spec. It could also mean the key in question is optional at this point in the code."
              (html-escape (format-expr problem)))})

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
