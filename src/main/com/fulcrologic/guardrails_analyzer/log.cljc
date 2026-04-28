(ns com.fulcrologic.guardrails-analyzer.log
  "Thin logging wrapper used by the analyzer library. On the JVM this
   delegates to `clojure.tools.logging`. On ClojureScript it uses
   `js/console` with level filtering via a dynamic var.

   Provides a timbre-compatible subset: `trace`, `debug`, `info`,
   `warn`, `error`, and `spy` (which logs an expression's value at a
   given level and returns it)."
  #?(:cljs (:require-macros [com.fulcrologic.guardrails-analyzer.log]))
  #?(:clj (:require [clojure.tools.logging :as tl])))

(def ^:private level-order
  "Numeric ordering used by the CLJS console backend to filter logs."
  {:trace 0 :debug 1 :info 2 :warn 3 :error 4})

#?(:cljs
   (def ^:dynamic *min-level*
     "Minimum log level the CLJS backend will emit. Messages at a lower
      level are dropped. Set via `binding` to change at runtime."
     :info))

#?(:cljs
   (defn- enabled?
     "Returns true when `level` is at or above the current `*min-level*`."
     [level]
     (>= (level-order level 0)
         (level-order *min-level* 0))))

#?(:cljs
   (defn- ->console
     "Dispatches `args` to the browser console at `level`."
     [level args]
     (when (enabled? level)
       (let [msg (apply pr-str args)]
         (case level
           :trace (js/console.debug msg)
           :debug (js/console.debug msg)
           :info  (js/console.info msg)
           :warn  (js/console.warn msg)
           :error (js/console.error msg))))))

#?(:cljs
   (defn log*
     "ClojureScript runtime logger. `level` is one of `:trace`,
      `:debug`, `:info`, `:warn`, or `:error`."
     [level & args]
     (->console level args)))

#?(:clj
   (defmacro trace
     "Logs `args` at TRACE level."
     [& args]
     (if (:ns &env)
       `(log* :trace ~@args)
       `(tl/logp :trace ~@args))))

#?(:clj
   (defmacro debug
     "Logs `args` at DEBUG level."
     [& args]
     (if (:ns &env)
       `(log* :debug ~@args)
       `(tl/logp :debug ~@args))))

#?(:clj
   (defmacro info
     "Logs `args` at INFO level."
     [& args]
     (if (:ns &env)
       `(log* :info ~@args)
       `(tl/logp :info ~@args))))

#?(:clj
   (defmacro warn
     "Logs `args` at WARN level."
     [& args]
     (if (:ns &env)
       `(log* :warn ~@args)
       `(tl/logp :warn ~@args))))

#?(:clj
   (defmacro error
     "Logs `args` at ERROR level. When the first argument is a
      `Throwable`, `clojure.tools.logging` attaches it to the log
      entry in the usual way."
     [& args]
     (if (:ns &env)
       `(log* :error ~@args)
       `(tl/logp :error ~@args))))

#?(:clj
   (defmacro spy
     "Evaluates `expr`, logs `[message value]` at `level` (default
      `:debug`), and returns the value. Compatible with the subset of
      `taoensso.timbre/spy` used in this code base.

      Supported arities:
        (spy expr)
        (spy level expr)
        (spy level message expr)

      When used inside a threading macro, the final `expr` is what
      gets threaded through."
     ([expr]
      `(spy :debug nil ~expr))
     ([level expr]
      `(spy ~level nil ~expr))
     ([level message expr]
      (let [cljs?   (some? (:ns &env))
            v-sym   (gensym "spy-val")
            log-fn  (if cljs? `log* `tl/logp)
            has-msg (some? message)]
        `(let [~v-sym ~expr]
           ~(if has-msg
              `(~log-fn ~level ~message ~v-sym)
              `(~log-fn ~level ~v-sym))
           ~v-sym)))))
