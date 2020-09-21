(ns com.fulcrologic.guardrails-pro.artifacts-spec
  (:require
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification component assertions]]))

(specification "insert-into-indexes" :unit
  (let [make-problem (fn [file sym line col]
                       #::grp.art{:file (str "file#" file)
                                  :line-start line
                                  :column-start col
                                  :checking-sym (symbol (str "sym#" sym))})
        error1 (make-problem 1 1 1 1)
        error2 (make-problem 1 2 2 1)
        error3 (make-problem 1 3 1 1)
        error4 (make-problem 1 1 1 2)]
    (assertions
      (grp.art/insert-into-indexes {} ::grp.art/errors error1)
      =check=>
      (_/embeds?* {::grp.art/errors
                   #::grp.art{:indexed {"file#1" {1 {1 [error1]}}}
                              :by-sym {'sym#1 [error1]}
                              :sym->index-paths {'sym#1 [["file#1" 1 1]]}}})
      (reduce #(grp.art/insert-into-indexes %1 ::grp.art/errors %2)
        {} [error1 error2 error3 error4])
      =check=>
      (_/embeds?* {::grp.art/errors
                   #::grp.art{:indexed {"file#1" {1 {1 [error1 error3]
                                                     2 [error4]}
                                                  2 {1 [error2]}}}
                              :by-sym {'sym#1 [error1 error4]
                                       'sym#2 [error2]
                                       'sym#3 [error3]}
                              :sym->index-paths {'sym#1 [["file#1" 1 1] ["file#1" 1 2]]
                                                 'sym#2 [["file#1" 2 1]]
                                                 'sym#3 [["file#1" 1 1]]}}}))))

(specification "clear-problems!" :unit
  (let [make-problem (fn [file sym line col]
                       #::grp.art{:file (str "file#" file)
                                  :line-start line
                                  :column-start col
                                  :checking-sym (symbol (str "sym#" sym))})
        error1 (make-problem 1 1 1 1)
        error2 (make-problem 1 2 2 1)
        error3 (make-problem 1 3 1 1)
        error4 (make-problem 1 1 1 2)]
    (assertions
      (grp.art/clear-problems
        (reduce #(grp.art/insert-into-indexes %1 ::grp.art/errors %2)
          {} [error1 error2 error3 error4])
        'sym#1)
      =check=> (_/embeds?*
                 {::grp.art/errors
                  #::grp.art{:indexed {"file#1" {1 {1 [error3]
                                                    2 []}
                                                 2 {1 [error2]}}}
                             :by-sym {'sym#1 []
                                      'sym#2 [error2]
                                      'sym#3 [error3]}
                             :sym->index-paths {'sym#2 [["file#1" 2 1]]
                                                'sym#3 [["file#1" 1 1]]}}}))))
