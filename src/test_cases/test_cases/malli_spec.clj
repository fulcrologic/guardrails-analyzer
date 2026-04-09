(ns test-cases.malli-spec
  (:require
   [clojure.string :as str]
   [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
   [com.fulcrologic.guardrails-analyzer.test-cases-runner :refer [deftc]]
   [com.fulcrologic.guardrails-analyzer.test-checkers :as tc]
   [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]]
   [fulcro-spec.check :as _]))

(>defn malli-identity [x] [:string => :string]              ; :binding/malli.identity
       x)

(>defn malli-bad-return [] [=> :int]                        ; :problem/malli.bad-return
       "not-an-int")

(>defn malli-nullable [s] [(? :string) => :string]          ; :binding/malli.nullable
       (or s "default"))

;; Calls to core functions with malli fdefs

(>defn malli-inc [x] [:int => :int]                         ; :binding/malli.inc
       (inc x))

(>defn malli-bad-inc [s] [:string => :int]                  ; :binding/malli.bad-inc-arg :problem/malli.bad-inc-return
       (inc s))                                                  ; :problem/malli.bad-inc

(>defn malli-str-upper [s] [:string => :string]             ; :binding/malli.str-upper
       (str/upper-case s))

(>defn malli-bad-str-upper [n] [:int => :string]            ; :binding/malli.bad-str-upper-arg
       (str/upper-case n))                                       ; :problem/malli.bad-str-upper

(deftc
  {:binding/malli.identity
   {:message  "Malli >defn binds arg with :string samples"
    :expected (_/embeds?* {::cp.art/samples (_/every?* (_/is?* string?))})}

   :problem/malli.bad-return
   {:message  "Malli >defn detects bad return type"
    :expected {::cp.art/problem-type :error/bad-return-value}}

   :binding/malli.nullable
   {:message  "Malli >defn handles (? :string) nullable spec"
    :expected (_/embeds?* {::cp.art/samples (_/is?* seq)})}

   :binding/malli.inc
   {:message  "Malli >defn binding for inc arg"
    :expected (_/embeds?* {::cp.art/samples (_/every?* (_/is?* int?))})}

   :binding/malli.bad-inc-arg
   {:message  "Malli >defn binding for bad-inc arg"
    :expected (_/embeds?* {::cp.art/samples (_/every?* (_/is?* string?))})}

   :problem/malli.bad-inc-return
   {:message  "Malli >defn detects bad return from inc on string"
    :expected {::cp.art/problem-type :error/bad-return-value}}

   :problem/malli.bad-inc
   {:message  "Malli >defn catches string passed to inc (expects number)"
    :expected {::cp.art/problem-type :error/function-argument-failed-spec}}

   :binding/malli.str-upper
   {:message  "Malli >defn binding for str-upper arg"
    :expected (_/embeds?* {::cp.art/samples (_/every?* (_/is?* string?))})}

   :binding/malli.bad-str-upper-arg
   {:message  "Malli >defn binding for bad-str-upper arg"
    :expected (_/embeds?* {::cp.art/samples (_/every?* (_/is?* int?))})}

   :problem/malli.bad-str-upper
   {:message  "Malli >defn catches int passed to str/upper-case (expects string)"
    :expected {::cp.art/problem-type :error/function-argument-failed-spec}}})
