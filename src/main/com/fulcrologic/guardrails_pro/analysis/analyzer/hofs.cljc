(ns com.fulcrologic.guardrails-pro.analysis.analyzer.hofs
  (:require
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :as grp.ana.disp]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.literals]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.rpl.specter :as $]
    [taoensso.encore :as enc]
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

;; CONTEXT: ============ fn * -> val ============

;; TODO
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
  (let [lambda (grp.ana.disp/-analyze! env f)
        colls (map (partial grp.ana.disp/-analyze! env) colls)]
    {::grp.art/samples (grp.sampler/sample! env
                         (grp.art/external-function-detail env map-like-sym)
                         (cons lambda colls))}))

(defmethod grp.ana.disp/analyze-mm 'map [env sexpr] (analyze-map-like! env sexpr))
(defmethod grp.ana.disp/analyze-mm 'clojure.core/map [env sexpr] (analyze-map-like! env sexpr))

;; CONTEXT: ============ * -> fn ============

(defn analyze-constantly! [env [this-sym value]]
  (let [value-td (grp.ana.disp/-analyze! env value)]
    (merge
      (get-in (grp.art/external-function-detail env this-sym)
        [::grp.art/arities 1 ::grp.art/gspec ::grp.art/return-spec])
      #::grp.art{:lambda-name (gensym "constantly$")
                 :env->fn (fn [env] (fn [& _] (rand-nth (vec (::grp.art/samples value-td)))))})))

(defmethod grp.ana.disp/analyze-mm 'constantly [env sexpr] (analyze-constantly! env sexpr))
(defmethod grp.ana.disp/analyze-mm 'clojure.core/constantly [env sexpr] (analyze-constantly! env sexpr))

(defn update-fn-ref [{:as function ::grp.art/keys [fn-ref env->fn]} env f]
  (cond
    fn-ref (update function ::grp.art/fn-ref f)
    env->fn (assoc function ::grp.art/env->fn
              (f (env->fn env)))
    :else function))

;; TODO
(defn analyze-comp! [env [this-sym & _]])

(defn analyze-complement! [env [this-sym f]]
  (-> (grp.ana.disp/-analyze! env f)
    (update-fn-ref env #(comp not %))
    (update ::grp.art/arities
      (partial enc/map-vals
        #(merge % #::grp.art{:return-spec boolean?
                             :return-type (pr-str boolean?)})))))

(defmethod grp.ana.disp/analyze-mm 'complement [env sexpr] (analyze-complement! env sexpr))
(defmethod grp.ana.disp/analyze-mm 'clojure.core/complement [env sexpr] (analyze-complement! env sexpr))

;; TODO
(defn analyze-fnil [env [this-sym & _]])

;; TODO
(defn analyze-juxt! [env [this-sym & fns]]
  (let [fns-td (map (partial grp.ana.disp/-analyze! env) fns)]
    {}
    #_(fn [& args]
        (reduce #(conj %1 (apply %2 args))
          [] (map (partial grp.sampler/get-fn-ref env) fns-td)))))

;; TODO
(defn analyze-partial [env [this-sym & _]])

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
