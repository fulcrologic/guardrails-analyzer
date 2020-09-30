(ns com.fulcrologic.guardrails-pro.analysis.analyzer.hofs
  (:require
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :as grp.ana.disp]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.literals]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [taoensso.timbre :as log]))

;; TODO potential duplication with >defn
(defn analyze-single-arity! [env [arglist gspec & body]]
  (let [gspec  (grp.fnt/interpret-gspec env arglist gspec)
        env    (grp.fnt/bind-argument-types env arglist gspec)
        result (grp.ana.disp/analyze-statements! env body)]
    (grp.fnt/check-return-type! env gspec result (last body))))

(defn location-of-lambda [lambda]
  ((juxt :line :column) (meta (first lambda))))

(defn analyze-lambda! [env lambda]
  (let [{::grp.art/keys [lambdas]} (grp.art/function-detail env (::grp.art/checking-sym env))
        lambda-td (get lambdas (location-of-lambda lambda) {})
        arities (drop-while (complement vector?) lambda)]
    (if (vector? (first arities))
      (analyze-single-arity! env arities)
      (doseq [arity arities]
        (analyze-single-arity! env arity)))
    lambda-td))

(defmethod grp.ana.disp/analyze-mm '>fn [env sexpr] (analyze-lambda! env sexpr))
(defmethod grp.ana.disp/analyze-mm `gr/>fn [env sexpr] (analyze-lambda! env sexpr))

;; TODO HOFs fn -> val
(comment
  reduce
  filter
  apply
  update
  sort-by
  group-by
  split-with
  partition-by
  swap!
  repeatedly
  iterate
  )

(defn analyze-map-like! [env [map-like-sym f & colls]]
  (let [lambda (log/spy :debug :lambda (grp.ana.disp/-analyze! env f))
        colls (map (partial grp.ana.disp/-analyze! env) colls)]
    {::grp.art/samples (grp.sampler/sample! env
                         (grp.art/external-function-detail env map-like-sym)
                         (cons lambda colls))}))

(defmethod grp.ana.disp/analyze-mm 'map [env sexpr] (analyze-map-like! env sexpr))
(defmethod grp.ana.disp/analyze-mm 'clojure.core/map [env sexpr] (analyze-map-like! env sexpr))

;; TODO HOFs * -> fn
(comment
  comp
  complement
  constantly
  partial
  fnil
  juxt
  )

(defn analyze-constantly! [env [const value]]
  (let [value-td (grp.ana.disp/-analyze! env value)]
    (merge
      (get-in (grp.art/external-function-detail env const)
        [::grp.art/arities 1 ::grp.art/gspec ::grp.art/return-spec])
      #::grp.art{:lambda-name (gensym "constantly$")
                 :env->fn (fn [env] (grp.sampler/random-sample-fn value-td))})))

(defmethod grp.ana.disp/analyze-mm 'constantly [env sexpr] (analyze-constantly! env sexpr))
(defmethod grp.ana.disp/analyze-mm 'clojure.core/constantly [env sexpr] (analyze-constantly! env sexpr))

;; TODO transducers
(comment
  into
  sequence
  transduce
  eduction
  map
  cat
  mapcat
  filter
  remove
  take
  take-while
  take-nth
  drop
  drop-while
  replace
  partition-by
  partition-all
  keep
  keep-indexed
  map-indexed
  distinct
  interpose
  dedupe
  random-sample
  )
