(ns test-cases.mix-lab
  "Sandbox namespace exercising spec1 + malli >defn together so we can
   inspect analyzer behavior, sample propagation through let bindings,
   path-aware errors, and mixed cross-calls."
  (:require
   [clojure.spec.alpha :as s]
   [com.fulcrologic.guardrails.core :as gr :refer [>defn => ?]]
   [com.fulcrologic.guardrails.malli.core :as gm]
   [com.fulcrologic.guardrails.malli.registry :as mreg]
   [malli.core :as m]))

;;; -- spec1 side ----------------------------------------------------

(s/def ::user-id int?)
(s/def ::name string?)
(s/def ::user (s/keys :req [::user-id ::name]))

(>defn spec-double
       "Spec1 >defn that doubles an int."
       [n]
       [int? => int?]
       (* 2 n))

(>defn spec-process-user
       "Spec1 >defn that takes a ::user map and returns its id."
       [u]
       [::user => int?]
       (::user-id u))

;;; -- malli side ----------------------------------------------------

(mreg/register! ::user-id :int)
(mreg/register! ::name :string)
(mreg/register! ::muser
                [:map [::user-id :int] [::name :string]])

(gm/>defn malli-double
          "Malli >defn that doubles an int."
          [n]
          [:int => :int]
          (* 2 n))

(gm/>defn malli-process-user
          "Malli >defn that takes a ::muser map and returns its id."
          [u]
          [::muser => :int]
          (::user-id u))

;;; -- intentional bugs (spec1) --------------------------------------

(>defn spec-bug-bad-arg
       "Calls spec-double with a string. Expect error/function-argument-failed-spec."
       []
       [=> int?]
       (spec-double "not an int"))                            ;; <-- bug

(>defn spec-bug-bad-return
       "Returns a string from int? slot. Expect error/bad-return-value."
       []
       [=> int?]
       "still wrong")                                          ;; <-- bug

(>defn spec-bug-bad-keymap
       "Constructs a ::user map with a bad ::user-id and passes it on.
        Expect spec violation when spec-process-user receives this map."
       []
       [=> int?]
       (let [u {::user-id "abc" ::name "Alice"}]              ;; <-- bug
         (spec-process-user u)))

;;; -- intentional bugs (malli) --------------------------------------

(gm/>defn malli-bug-bad-arg
          "Calls malli-double with a string. Expect error/function-argument-failed-spec."
          []
          [=> :int]
          (malli-double "not an int"))                        ;; <-- bug

(gm/>defn malli-bug-bad-return
          "Returns a string from :int slot. Expect error/bad-return-value."
          []
          [=> :int]
          "still wrong")                                       ;; <-- bug

(gm/>defn malli-bug-bad-keymap
          "Constructs a ::muser map with a bad ::user-id."
          []
          [=> :int]
          (let [u {::user-id "abc" ::name "Alice"}]           ;; <-- bug
            (malli-process-user u)))

;;; -- mixed cross calls ---------------------------------------------

(gm/>defn malli-calls-spec
          "Malli function passing through to spec1 function."
          [n]
          [:int => :int]
          (spec-double n))

(>defn spec-calls-malli
       "Spec1 function passing through to malli function."
       [n]
       [int? => int?]
       (malli-double n))

(gm/>defn malli-calls-spec-bad
          "Wrong type at the boundary into spec-double."
          [s]
          [:string => :int]
          (spec-double s))                                    ;; <-- bug

(>defn spec-calls-malli-bad
       "Wrong type at the boundary into malli-double."
       [s]
       [string? => int?]
       (malli-double s))                                      ;; <-- bug

;;; -- let-binding sample propagation --------------------------------

(>defn spec-let-good
       "Sample-propagation through let bindings, all good."
       [n]
       [int? => int?]
       (let [a (inc n)
             b (* 2 a)]
         b))

(>defn spec-let-bad
       "Sample-propagation through let bindings, then misuse."
       [n]
       [int? => int?]
       (let [a (inc n)
             s (str a)]
         (inc s)))                                             ;; <-- bug: inc on string

(gm/>defn malli-let-bad
          "Sample-propagation through let in malli, then misuse."
          [n]
          [:int => :int]
          (let [a (inc n)
                s (str a)]
            (inc s)))                                          ;; <-- bug: inc on string

;;; -- path-based / pure conditional ---------------------------------

(>defn spec-path-good
       "Pure predicate (even? n) — both branches return ints."
       [n]
       [int? => int?]
       (if (even? n) (* 2 n) (inc n)))

(>defn spec-path-bad
       "Pure predicate but else returns a string. Expect path-aware error."
       [n]
       [int? => int?]
       (if (even? n)
         (* 2 n)
         "oops"))                                              ;; <-- bug on else path

(gm/>defn malli-path-bad
          "Same shape as spec-path-bad but with malli."
          [n]
          [:int => :int]
          (if (even? n)
            (* 2 n)
            "oops"))                                           ;; <-- bug on else path

;;; -- if-let with path-based bindings --------------------------------

(>defn spec-iflet
       "if-let that should produce path-based bindings for `x`."
       [m]
       [(? ::user) => int?]
       (if-let [x (::user-id m)]
         (inc x)
         0))
