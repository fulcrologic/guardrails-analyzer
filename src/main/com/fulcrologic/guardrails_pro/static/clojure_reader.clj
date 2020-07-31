(ns com.fulcrologic.guardrails-pro.static.clojure-reader
  "A custom Clojure reader that puts source file location metadata on every form that can *have* metadata (like CLJS does)
   so that we can report information more accurately.

   Here's the idea: We cannot *fix* the Clojure reader itself. Even if we found a hack, it could lead to badness.

   So instead *this* reader is called from the `>defn` macro. Meaning:

   1. Clojure reads the file.
   2. Clojure does macro expansion.
   3. `>defn`, when being macroexpanded (run), calls *this* reader to re-read just the function (itself)
   4. We use that newly read form instead of `&form` in order to capture better source location info.
   ")

(defn read-form
  "Read the form that starts on `starting-line` of the given `namespace` from the source file, and returns that
  form. The returned form will be augmented so that *everthing* that
  *can* have metadata will include file/line/start column/ending column information. `lang` can be `:clj` or `:cljs`
  to indicate which feature of conditional reads should be used. Defaults to `:clj`."
  ([namespace starting-line] (read-form namespace starting-line :clj))
  ([namespace starting-line lang])
  ;; TASK: Implement this. Might need to steal original Clojure reader source and just augment it??? Tools reader might work.
  ;; NOTE: It is OK to drop the `lang` argument. We really only need this to fix Clojure.
  )
