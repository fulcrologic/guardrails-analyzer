(ns com.fulcrologic.guardrails-pro.artifacts-spec
  (:require
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails.core :refer [=>]]
    [com.fulcrologic.guardrails-pro.core :refer [>defn]]
    [fulcro-spec.core :refer [specification component assertions when-mocking! =fn=>]]
    [clojure.spec.alpha :as s]))

(defmacro >fn [& args] `(fn ~@args))
(defn >fspec [& args])
(defn generate [typ])

(comment
  (>ftag map
    [f s]
    ^:pure-if-arg1-pure [[any? => any?] (s/coll-of any?)]
    ))

(>defn sample [f m]
  ;; We may get f via a param, in which case it is just a pure stub that when called generates the output type
  [[pos-int? => pos-int?] map? => map?]
  (let [b   44
        c   {:a 1
             :b {:c (inc b)
                 :d (map f [1 2 (+ 1 (inc b))])
                 :e (filterv (>fn [y] ^:pure [int? => boolean?]
                               ;; 1 => b
                               (odd? (+ 1 y)))
                      (range 1 20))
                 :f f
                 ;; if :g was nsed, then it could have a spec of coll-of fpsec, which would generalize our ability to
                 ;; analyze in cases where a function call was used here to make the list of functions.
                 :g [inc dec]}}
        h   (map f [1 2 3] [4 5 6] [7 8 9])
        f   (get-in m [:thing/bob :stupid/function])
        c2  (update-in c [:b :g] conj f)
        ??? (get-in c [:b :c])
        g   (get-in c [:b :f])
        g2  (get-in c2 [:b :g 2])                           ; VERY context-dependent on c2 being a literal we can analyze
        h   (g 12)
        i   (g2 11)]
    c))


(comment
  (>defn some-f [x]
    [(s/select ::person [:person/name :person/address [:address/id :address/function]])
     => any?])
  ;; What is the type description for c?
  ;; 1. We need to be able to generate a sample
  ;; 2. We need to be able to check the body of the anon functions:
  ;; 2a. `b` needs to be visible in the env of inner fn
  ;; 3. When grabbing an entry from the sample, we need

  ;; as we gen the sample, we can check the args of anything
  ;; A sample of a function is the function itself.
  {::grp.art/recursive-description {:a 1
                                    :b {:c 45
                                        :d (lazy-seq -39 0 29485 -33) ; random, since f isn't a real f in analysis
                                        :e [45 47 49]
                                        ;; We make up a pure function
                                        :f (with-meta
                                             (fn [x] (generate pos-int?))
                                             {:fn-spec (>fspec [pos-int? => pos-int?])})}}}

  ;; What about calling sample, with functions that have looser specs than required:
  (sample inc))
;; Case 1: If has RV gen, we could test that when inc is passed pos-int it always returns pos-int
;; Case 2: If not would should issue a warning that the function may not behave properly in the context; however,
;; this is likely to be a false positive.

