(ns com.fulcrologic.guardrails-pro.analysis.analyzer.hofs
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :as grp.ana.disp]
    [com.fulcrologic.guardrails-pro.analysis.analyzer.literals]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
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
    (-> (get-in (grp.art/external-function-detail env this-sym)
          [::grp.art/arities 1 ::grp.art/gspec ::grp.art/return-spec])
      (merge #::grp.art{:fn-name (gensym "constantly$")
                        :fn-ref (fn [& _] (rand-nth (vec (::grp.art/samples value-td))))})
      (assoc-in [::grp.art/arities :n ::grp.art/gspec ::grp.art/sampler]
        ::grp.sampler/pure))))

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

(defn analyze-fnil! [env [this-sym f & nil-patches :as this-expr]]
  (let [nil-patches-td (map (partial grp.ana.disp/-analyze! env) nil-patches)
        function (grp.ana.disp/-analyze! env f)
        fnil-args-td (cons function nil-patches-td)]
    (grp.fnt/validate-argtypes!? env
      (grp.art/get-arity
        (::grp.art/arities (grp.art/external-function-detail env this-sym))
        fnil-args-td)
      fnil-args-td)
    (-> function
      (update-fn-ref env #(apply fnil % (mapv (comp rand-nth vec ::grp.art/samples) nil-patches-td)))
      (update ::grp.art/arities
        (partial enc/map-vals
          (fn [arity]
            (-> arity
              (update ::grp.art/gspec
                (fn [gspec]
                  (-> gspec
                    (update ::grp.art/argument-specs
                      (fn [arg-specs]
                        (mapv (fn [spec patch]
                                (cond-> spec (not= ::not-found patch)
                                  (s/nilable)))
                          arg-specs (concat nil-patches (repeat ::not-found)))))
                    (update ::grp.art/argument-types
                      (fn [arg-specs]
                        (mapv (fn [-type patch]
                                (cond-> -type (some? patch)
                                  (#(str "(nilable " % ")"))))
                          arg-specs (concat nil-patches (repeat nil))))))))
              (as-> arity
                (update-in arity [::grp.art/gspec ::grp.art/argument-predicates]
                  (fnil conj [])
                  (fn [& args]
                    (grp.fnt/validate-argtypes!? env arity
                      (map (fn [arg patch]
                             (let [value (if (some? arg) arg patch)]
                               (hash-map
                                 ::grp.art/samples #{value}
                                 ::grp.art/original-expression value)))
                        args (map (comp rand-nth vec ::grp.art/samples) nil-patches-td))))
                  (partial grp.fnt/validate-arguments-predicate!? env arity))))))))))

(defmethod grp.ana.disp/analyze-mm 'fnil [env sexpr] (analyze-fnil! env sexpr))
(defmethod grp.ana.disp/analyze-mm 'clojure.core/fnil [env sexpr] (analyze-fnil! env sexpr))

;; TODO
(defn analyze-juxt! [env [this-sym & fns]]
  (let [fns-td (map (partial grp.ana.disp/-analyze! env) fns)]
    {}
    #_(fn [& args]
        (reduce #(conj %1 (apply %2 args))
          [] (map (partial grp.sampler/get-fn-ref env) fns-td)))))

;; TODO
(defn analyze-partial! [env [this-sym & _]])

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
