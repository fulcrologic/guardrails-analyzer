(ns com.fulcrologic.guardrails-pro.runtime.function-invocation
  (:require
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as a]))

(defn check-call
  "Check a call to the function defined by the given resitry-entry with the given expanded args."
  [env registry-entry expanded-args])

(comment
  (check-call
    {::a/registry {}}
    {::a/name           `f
     ::a/last-changed   0
     ::a/fn             (fn [n] (inc n))
     ::a/arities        {1 {::a/arglist '[n]
                            ::a/body    '[(with-meta (inc n) {:file "f" :line 23})]
                            ::a/gspec   {::a/arg-types   ["int?"]
                                         ::a/arg-specs   [int?]
                                         ::a/return-type "int?"
                                         ::a/return-spec int?}}}
     ::a/extern-symbols {}}
    [{::a/type                "int?"
      ::a/spec                int?
      ::a/original-expression 'x
      ;; TASK: A type could have a "source", which would be some kind of path. For example, say you had a function
      ;; (let [a (g)
      ;;       b (f a)] (h b))
      ;; If we use `f` itself to generate values into `b`, then it might be useful to be able to say "h fails with sample ... which could be a result of `(f a)`.
      ;; Perhaps that is overkill, but knowing that the argument expression was `b` is probably reasonable.
      ::a/samples             #{-1 0 1}}])
  ;; TASK: Resume here. Checking a call, once you've resolved the argument types, is the bulk of the interesting work.
  ;; The return from check-call should be the type, but it should also be the problems. It would be nice if we could
  ;; track the source of types, so that we can make back-references in the code.
  )