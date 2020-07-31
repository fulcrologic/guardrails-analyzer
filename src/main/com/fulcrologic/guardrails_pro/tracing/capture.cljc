(ns com.fulcrologic.guardrails-pro.tracing.capture
  "This ns has algorithms for converting input expressions into traced expressions where the elements of the
   original expression will be captured at runtime.")

(defn spy
  "Record that the execution of the expression at `location` has resulted in `value`."
  [value location]
  ;; TASK: Send the value information to the correct recording spot based on `location`
  value)

;; TASK: The input form in compile env will need to include sufficient information to construct location info. We probably
;; need to ADD UUIDs to each form element that can *have* metadata, and then we can use that to point to a particular
;; item in the expression as we spy on it.

;; TASK: Properly displaying this information next to the real user source in CLJS should be easy, because we have accurate
;; metadata from the compiler. In CLJ we'll need to do some level of "fuzzy" matching, because it only adds location info
;; to lists. We can, however, "use the source"...i.e. we can read the source from disk using classpath (source files are
;; resources), find the function of interest that is being compiled (via top-level metadata) and process it accordingly. In fact,
;; we could actually leverage a customized Clojure Reader to re-read that bit of source and put the same amount of metadata
;; on the forms as we get from CLJS (making sure to target the CLJ lang feature only).

;; TASK: figure out what goes in this env. We're trying to rewrite an s-expression so that we get a bit-by-bit view
;; into the data as it flows through it, but in a way we can correlate to the original source. usually this will mean
;; outputting some kind of `let` or perhaps just a `spy` that knows how to dispatch the value to a particular place in
;; the runtime system.
;; TASK: Figure out how we'll do nested use of macros. Consider the common cases like `->` that might have nested `cond->>`.
;; TASK: Note: some macros will simply not be tractable. For example, we might not be able to deal with core/async sincee
;; it runs on a diff thread and usually has data that "appears over time". Technically we can instrument it and capture it,
;; but we cannot control the timing of it. Might still be interesting, in that having the data "show up" in the UI as it
;; arrives would still be massively useful in development....but you'd have to be careful if you were keeping multiple
;; samples of executions, since you would not necessarily know which to use asynchronously.
(defmulti instrument (fn [compile-env])
  ;; TASK: find s-expression's details for dispatch
  ;; TASK: unify cljs.core and clojure.core into clojure.core to allow single dispatch definition. Same for other code things that are auto-aliased in cljs
  )


(defn let-like? [compile-env]
  (let [sym (::some-lookup compile-env)
        nm  (name sym)]
    ;; TASK: look to see if dispatch symbol, then use extensible set of syms...be sure to handle clj and cljs
    (contains? #{"let" "when-let" "if-let" "binding" "with-redefs"} nm)))

(defn instrument-let-like [env]
  ;; TASK: implementation of instrument for things that do std let bindings
  )

(defmethod instrument :default
  [compile-env]
  ;; TASK: Default is simply spy on the full expression itself and report the result according to the metadata "location"
  ;; of the original form

  ;; TASK: Check for `let`-like known macros, and process them from here
  (if (let-like? compile-env)
    (instrument-let-like compile-env))
  )

(defmethod instrument 'clojure.core/let
  [compile-env]
  ;; TASK: Default is simply spy on the full expression itself and report the result according to the metadata "location"
  ;; of the original form
  (instrument-let-like compile-env))
