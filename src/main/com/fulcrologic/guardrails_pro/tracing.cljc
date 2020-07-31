(ns com.fulcrologic.guardrails-pro.tracing
  "The tracing feature of GRP, when enabled, causes the `>defn` macro to rewrite the function body so that each
   expression \"of interest\" is value-captured (spied on) in a location-aware manner so that the data flow can
   be visualized in the development UI.

   This feature includes the following facets:

   * The ability to indicate how many samples (of history) should be captured
   * The ability to turn on/off capture on a per-function/namespace basis to prevent runtime overhead
   * The ability to capture all of the inputs to the function, so that an edited version of the function can be
     re-triggered with the same inputs for the purposes of re-capture and analysis on user demand.
   * The ability to focus the UI on a particular function and see what values flowed through the code
   * Extensible: macros and special forms, unless added, cannot be understood in much of a useful way by tracing as
     anything more than black boxes. However, supporting expansion through the `instrument` multi-method
     (which can dispatch on the macro's name) allows for custom code rewriting that can provide more
     fine-grained tracing control.

   For example:

   ```
   (-> a
     (f)
     (g x))
   ```

   might be rewritten as:

   ```
   (let [a# (spy a location-info)
         b# (spy (f a#) location-info)]
     (spy (g b# x) location-info))
   ```

   where `location-info` is the metadata information about the containing function and the original source location
   of the expression being spied upon and `spy` is a function that knows how to report the expression information so
   that the UI can associate it with the function/source location for user consumption.

   See the `capture.cljc` namespace for the implementation of this instrumentation step.
   ")
