(ns com.fulcrologic.guardrails-pro.static.analyzer
  "The analyzer for expressions. An extensible process where recognition of special forms can happen.

   The type of an expression is often determined at this phase (e.g. for literals), and this phase is
   also responsible for


   The type description of ANY expression is defined by:

   ```
   (type-description env expr)
   ```

   which is a mutually-recursive algorithm with the following steps:

   * Call `analyze` on the expression.
   * If it is a call to a function in the registry, then we dispatch to a multimethod that can
     do proper steps for type extraction (which may call analyze on arguments to get type
     descriptors).
   ** Analyze may find it to be trivially a type (e.g. primitive/literal)
   ** The analyzer can detect special forms (i.e. `let`) and process them, which in turn
      will need to ask for the `type-description` of each binding value.
      NOTE: There can be any number of errors detected during this analysis.
   ** The end result is a type-description (which can include nested errors)
   ")

(defmulti analyze (fn [env sexpr]))

(defmethod analyze :literal [env sexpr]
  ;::a/type-description
  )

(defmethod analyze :function-call [env sexpr]
  ;(function-type ...)
  )

(defmethod analyze :do [env sexpr])

(defmethod analyze :let-like [env sexpr]
  ;; Walk each binding, recursively calling analyze, while adding bindings to env

  ;; collect type description errors from all bindings and "floating" expressions
  (comment
    (let [a 1                                                 ; Add type description for 1 to env -> env'
          ;; TODO: handle destructuring...
          {::person/keys [name]} ^{::a/spec int?} (<! some-channel)                        ; bind `name` into env with spec ::person/name
          b (f 2)]                                            ; call analyze on `(f 2)` and bind type-desc to b in env''
      (do
        (j) ; errors
        (k) ; errors
        ;; cumulative errors need to be returned from do
        (l))                                                    ;; results in type description we "lost"...need to capture
      (g b)))                                                  ; grand return of `let` is the type description of the LAST expression, but with cumulative ::errors
  )

;; (analyze env `(let [a 2] (f a)))
;; 1. dispatch to `let` analyzer, which:
;;   a. binds a to type-descrip for 2
;;   b. runs (analyze env `(f a)) (w/a bound in env)
;;      i. analyze does not recognize `f` as special (uses :default), so it calls `(function-type `f [type-description-a])`

;; (analyze env `(let [a (map + [1 2 3])] (f a)))
;; 1. dispatch to `let` analyzer, which:
;;   a. recursively calls (analyze env `(map + [1 2 3]))
;;      i. no special for map, so calls (function-type `map [type-desc-for-+ type-desc-for-vector-of-int])
;;      ii. Assume we've registered a gspec for + (pure) and map (seq of HOF on 1).
;;      iii. function-type detects it is safe to call + on each element of the sample sequence, to produce new sequence.
;;   b. new sequence of returned samples is bound into `a`
;;   c. runs (analyze env `(f a)) (w/a bound in env)
;;      i. analyze does not recognize `f` as special (uses :default), so it calls `(function-type `f [type-description-a])`
