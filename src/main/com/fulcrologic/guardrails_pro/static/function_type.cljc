(ns com.fulcrologic.guardrails-pro.static.function-type
  "Extensible mechanism for calculating the type description of a function call."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [com.fulcrologic.guardrails-pro.interpreter :as grp.i]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]
    [com.fulcrologic.guardrails.core :refer [>defn => |]]
    [taoensso.timbre :as log]))

(defmulti calculate-function-type
  "Use `get-calculate-function-type`. This is a multimethod that you use defmethod on to extend the type recognition system."
  (fn [env sym argtypes]
    (let [{::a/keys [arities]} (a/function-detail env sym)
          {::a/keys [gspec]} (get arities (count argtypes) :n)
          {::a/keys [pure? typecalc]} gspec]
      (cond
        pure? :pure
        typecalc (cond
                   (keyword? typecalc) typecalc
                   (map? typecalc) (::a/dispatch typecalc)
                   (vector? typecalc) (first typecalc)
                   :else (do
                           (log/error "Typecalc invalid:" typecalc)
                           :default))
        :else :default))))

(defn validate-argtypes [{::a/keys [arg-specs arg-predicates]} argtypes]
  (let [spec-arg-errors (mapcat
                          (fn [[arg-spec {::a/keys [samples original-expression]}]]
                            (mapcat
                              (fn [sample]
                                (some->>
                                  (s/explain-data arg-spec sample)
                                  ::s/problems
                                  (map (fn [e]
                                         {::a/original-expression original-expression
                                          ::a/expected (:pred e)
                                          ::a/actual (:val e)}))))
                              samples))
                          (map vector arg-specs argtypes))
        arg-pred-errors (apply mapcat (fn [& sample-arglist]
                                        (keep (fn [pred]
                                                (or (apply pred sample-arglist)
                                                  {::a/original-expression (map ::a/original-expression argtypes)
                                                   ::a/expected pred
                                                   ::a/actual sample-arglist}))
                                          arg-predicates))
                          (map ::a/samples argtypes))]
    (concat
      spec-arg-errors
      arg-pred-errors)))

(defn try-sampling [{::a/keys [return-spec generator return-predicates]}]
  (try
    (gen/sample
      (or generator (s/gen return-spec)))
    (catch #?(:clj Exception :cljs :default) _
      (log/info "Cannot sample from:" (or generator return-spec))
      nil)))

(defmethod calculate-function-type :default [env sym argtypes]
  (let [{::a/keys [arities]} (a/function-detail env sym)
        {::a/keys [gspec]} (get arities (count argtypes) :n)
        {::a/keys [return-type return-spec]} gspec]
    {::a/spec return-spec
     ::a/type return-type
     ::a/samples (try-sampling gspec)
     ::a/errors (validate-argtypes gspec argtypes)}))

(defmethod calculate-function-type :pure [env sym argtypes]
  (let [{::a/keys [arities fn-ref]} (a/function-detail env sym)
        {::a/keys [gspec]} (get arities (count argtypes) :n)]
    {::a/samples (apply map fn-ref (map ::a/samples argtypes))
     ::a/errors (validate-argtypes gspec argtypes)}))

(defmethod calculate-function-type :HOF [env sym argtypes]
  ;; TASK: This one dispatches on [:HOF arg-number]. The arg number indicates which argument of the function
  ;; is the HOF of interest. For example, `reduce` should use [:HOF 0] to indicate that its return type is
  ;; whatever type its first argument (a HOF) returns.
  )

;; # => fn*   (setting that says fn* are always pure)
;; (map #(+ 1 2) [1 2 3])
;; #{[1 2 3] [4 2] ...}
(defmethod calculate-function-type :map-like [env sym argtypes]
  ;; TASK:
  )
