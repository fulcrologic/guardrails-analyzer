(ns com.fulcrologic.copilot.generators
  #?(:cljs (:require-macros [com.fulcrologic.copilot.generators]))
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]))

#?(:clj
   (defmacro stub-impl-of [protocol]
     (let [{:keys [sigs]} (var-get
                            (find-var
                              (if (namespace protocol)
                                protocol
                                (symbol (str *ns*)
                                  (name protocol)))))]
       `(reify ~protocol
          ~@(mapcat
              #(for [arglist (:arglists (val %))]
                 (list
                   (:name (val %))
                   arglist
                   (key %)))
              sigs)))))

#?(:clj
   (defmacro stub-spec [protocol]
     `(s/with-gen #(satisfies? ~protocol %)
        #(gen/return (stub-impl-of ~protocol)))))
