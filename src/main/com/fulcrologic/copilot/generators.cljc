;; Copyright (c) Fulcrologic, LLC. All rights reserved.
;;
;; Permission to use this software requires that you
;; agree to our End-user License Agreement, legally obtain a license,
;; and use this software within the constraints of the terms specified
;; by said license.
;;
;; You may NOT publish, redistribute, or reproduce this software or its source
;; code in any form (printed, electronic, or otherwise) except as explicitly
;; allowed by your license agreement..

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
