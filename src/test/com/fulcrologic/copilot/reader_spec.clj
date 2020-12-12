(ns com.fulcrologic.copilot.reader-spec
  (:require
    [com.fulcrologic.copilot.reader :as cp.reader]
    [com.fulcrologicpro.clojure.tools.reader :as reader]
    [fulcro-spec.core :refer [specification assertions]]))

(defn test:parse-ns [s]
  (cp.reader/parse-ns (reader/read-string s)))

(specification "parse-ns"
  (assertions
    (test:parse-ns "(ns X (:require one))")
    => {}
    (test:parse-ns "(ns X (:require [one]))")
    => {}
    (test:parse-ns "(ns X (:require [one :as o]))")
    => {:aliases {'o 'one}}
    (test:parse-ns "(ns X (:require [one :refer [a]]))")
    => {:refers  {'a 'one/a}}
    (test:parse-ns "(ns X (:require [one :as o :refer [a]]))")
    => {:aliases {'o 'one}
        :refers  {'a 'one/a}}
    (test:parse-ns "(ns X (:require [pre [one :as po :refer [x]]]))")
    => {:aliases {'pre.po 'one}
        :refers  {'x 'pre.one/x}}
    (test:parse-ns "(ns X (:require [one :as o] [two :as t]))")
    => {:aliases {'o 'one 't 'two}}))
