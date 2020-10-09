(ns com.fulcrologic.guardrails-pro.test-checkers
  #?(:cljs (:require-macros [com.fulcrologic.guardrails-pro.test-checkers]))
  (:require
    #?@(:cljs [[goog.string.format]
               [goog.string :refer [format]]])
    #?@(:clj [[com.fulcrologic.guardrails.impl.parser :as impl.parser]])
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :refer [analyze-mm]]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.literals :as grp.ana.lit]
    [fulcro-spec.check :refer [checker]]))

#?(:clj
   (defmacro >test-fn [& args]
     (let [parsed-fn (grp.art/fix-kw-nss (impl.parser/parse-fn args []))
           lambda (merge
                    {::grp.art/lambda-name `(quote ~(gensym ">test-fn$"))}
                    parsed-fn
                    {::grp.art/fn-ref `(fn ~@args)
                     ::test-fn? true})]
       `(grp.art/resolve-quoted-specs
          ~(::grp.art/spec-registry lambda)
          ~lambda))))

(defmethod analyze-mm :collection/map [env hashmap]
  (if ((every-pred ::test-fn? ::grp.art/arities) hashmap) hashmap
    (grp.ana.lit/analyze-hashmap! env hashmap)))

(defn of-length?* [exp-len]
  (checker [actual]
    (let [length (count actual)]
      (when-not (= exp-len length)
        {:actual actual
         :expected `(~'of-length?* ~exp-len)
         :message (format "Expected count to be %d was %d" exp-len length)}))))
