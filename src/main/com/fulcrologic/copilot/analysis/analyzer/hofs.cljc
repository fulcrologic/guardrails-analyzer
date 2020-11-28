(ns com.fulcrologic.copilot.analysis.analyzer.hofs
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as grp.ana.disp]
    [com.fulcrologic.copilot.analysis.analyzer.literals]
    [com.fulcrologic.copilot.analysis.function-type :as grp.fnt]
    [com.fulcrologic.copilot.analysis.sampler :as grp.sampler]
    [com.fulcrologic.copilot.analysis.spec :as grp.spec]
    [com.fulcrologic.copilot.artifacts :as grp.art]
    [com.fulcrologic.guardrails.core :as gr]
    [com.fulcrologic.guardrails.utils :as utils]
    [com.fulcrologicpro.com.rpl.specter :as $]))

;; TODO potential duplication with >defn
(defn analyze-single-arity! [env [arglist gspec & body]]
  (let [gspec  (grp.fnt/interpret-gspec env arglist gspec)
        env    (grp.fnt/bind-argument-types env arglist gspec)
        result (grp.ana.disp/analyze-statements! env body)]
    (grp.fnt/check-return-type! env gspec result (last body) (meta (last body)))))

(defn location-of-lambda [lambda]
  ((juxt :line :column) (meta (first lambda))))

(defn analyze-lambda! [env lambda]
  (let [{::grp.art/keys [lambdas]} (grp.art/function-detail env (::grp.art/checking-sym env))
        lambda-td (get lambdas (location-of-lambda lambda) {})
        arities   (drop-while (complement vector?) lambda)]
    (if (vector? (first arities))
      (analyze-single-arity! env arities)
      (doseq [arity arities]
        (analyze-single-arity! env arity)))
    lambda-td))

(defmethod grp.ana.disp/analyze-mm `gr/>fn [env sexpr] (analyze-lambda! env sexpr))

(defn update-fn-ref [{:as function ::grp.art/keys [fn-ref env->fn]} env f]
  (cond
    fn-ref (update function ::grp.art/fn-ref f)
    env->fn (assoc function ::grp.art/env->fn
                            (f (env->fn env)))
    :else function))

(defn >fn-ret [td]
  ($/transform [($/walker ::grp.art/gspec) ::grp.art/gspec]
    #(select-keys %
       [::grp.art/return-spec
        ::grp.art/return-type
        ::grp.art/return-predicates])
    (select-keys td [::grp.art/arities])))

(defn >fn-args [td]
  ($/transform [($/walker ::grp.art/gspec) ::grp.art/gspec]
    #(select-keys %
       [::grp.art/argument-specs
        ::grp.art/argument-types
        ::grp.art/argument-predicates])
    (select-keys td [::grp.art/arities])))

;; CONTEXT: ============ fn * -> val ============

(defn analyze-apply! [env [this-sym f & args]]
  (let [apply-td      (grp.art/external-function-detail env this-sym)
        function      (grp.ana.disp/-analyze! env f)
        [args-td args-coll-td] ((juxt drop-last last)
                                (map (partial grp.ana.disp/-analyze! env) args))
        fn-args-td    (conj (vec args-td) args-coll-td)
        apply-args-td (cons function fn-args-td)]
    (if-not (grp.fnt/validate-argtypes!? env
              (grp.art/get-arity
                (::grp.art/arities apply-td)
                apply-args-td)
              apply-args-td)
      {::grp.art/samples (grp.sampler/try-sampling! env
                           (grp.spec/generator env
                             (get-in (grp.art/get-arity
                                       (::grp.art/arities function)
                                       fn-args-td)
                               [::grp.art/gspec ::grp.art/return-spec]))
                           {::grp.art/original-expression function})}
      (grp.fnt/analyze-function-call! env function
        (concat args-td
          (apply map #(hash-map ::grp.art/samples #{%})
            (::grp.art/samples args-coll-td)))))))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/apply [env sexpr] (analyze-apply! env sexpr))

(defn analyze-map-like! [env [this-sym f & colls]]
  (let [map-td   (grp.art/external-function-detail env this-sym)
        func-td  (grp.ana.disp/-analyze! env f)
        colls-td (map (partial grp.ana.disp/-analyze! env) colls)]
    (grp.fnt/analyze-function-call! env map-td (cons func-td colls-td))))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/map [env sexpr] (analyze-map-like! env sexpr))

(defn analyze-reduce-like! [env [this-sym f init coll]]
  (let [reduce-td (grp.art/external-function-detail env this-sym)
        func-td   (grp.ana.disp/-analyze! env f)
        init-td   (grp.ana.disp/-analyze! env init)
        coll-td   (grp.ana.disp/-analyze! env coll)]
    (grp.fnt/analyze-function-call! env reduce-td [func-td init-td coll-td])))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/reduce [env sexpr] (analyze-reduce-like! env sexpr))

(defn analyze-some! [env [this-sym pred coll]]
  (let [some-td (grp.art/external-function-detail env this-sym)
        pred-td (grp.ana.disp/-analyze! env pred)
        coll-td (grp.ana.disp/-analyze! env coll)]
    (if-not (grp.fnt/validate-argtypes!? env
              (grp.art/get-arity (::grp.art/arities some-td) [pred-td coll-td])
              [pred-td coll-td])
      {::grp.art/samples (grp.sampler/try-sampling! env
                           (grp.spec/generator env
                             (get-in (grp.art/get-arity
                                       (::grp.art/arities some-td)
                                       [pred-td coll-td])
                               [::grp.art/gspec ::grp.art/return-spec])))}
      ;; TODO: validate that pred accepts coll elements
      (grp.fnt/analyze-function-call! env some-td [pred-td coll-td]))))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/some [env sexpr] (analyze-some! env sexpr))

(defn analyze-split-with! [env [this-sym pred coll]]
  (let [split-with-td (grp.art/external-function-detail env this-sym)
        pred-td       (grp.ana.disp/-analyze! env pred)
        coll-td       (grp.ana.disp/-analyze! env coll)]
    (if-not (grp.fnt/validate-argtypes!? env
              (grp.art/get-arity (::grp.art/arities split-with-td) [pred-td coll-td])
              [pred-td coll-td])
      {::grp.art/samples (grp.sampler/try-sampling! env
                           (grp.spec/generator env
                             (get-in (grp.art/get-arity
                                       (::grp.art/arities split-with-td)
                                       [pred-td coll-td])
                               [::grp.art/gspec ::grp.art/return-spec])))}
      ;; TODO: validate that pred accepts coll elements
      (grp.fnt/analyze-function-call! env split-with-td [pred-td coll-td]))))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/split-with [env sexpr] (analyze-split-with! env sexpr))

(defn analyze-swap! [env [this-sym a f & args]]
  (let [swap-td      (grp.art/external-function-detail env this-sym)
        atom-td      (grp.ana.disp/-analyze! env a)
        func-td      (grp.ana.disp/-analyze! env f)
        args-td      (map (partial grp.ana.disp/-analyze! env) args)
        swap-args-td (concat [atom-td func-td] args-td)]
    (if-not (grp.fnt/validate-argtypes!? env
              (grp.art/get-arity (::grp.art/arities swap-td) swap-args-td)
              swap-args-td)
      {::grp.art/samples (grp.sampler/try-sampling! env
                           (grp.spec/generator env
                             (get-in (grp.art/get-arity
                                       (::grp.art/arities func-td)
                                       (cons {} args-td))
                               [::grp.art/gspec ::grp.art/return-spec])))}
      (let [func-arg-td {::grp.art/samples
                         (grp.sampler/try-sampling! env
                           (grp.spec/generator env
                             (get-in (grp.art/get-arity
                                       (::grp.art/arities func-td)
                                       (cons {} args-td))
                               [::grp.art/gspec ::grp.art/argument-specs 0])))}]
        (grp.fnt/analyze-function-call! env func-td (cons func-arg-td args-td))))))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/swap! [env sexpr] (analyze-swap! env sexpr))

(defn analyze-update! [env [this-sym m k f & args]]
  (let [update-td      (grp.art/external-function-detail env this-sym)
        map-td         (grp.ana.disp/-analyze! env m)
        key-td         (grp.ana.disp/-analyze! env k)
        map-entry-td   {::grp.art/samples
                        (set (map (fn [[k m]] (k m))
                               (grp.sampler/random-samples-from-each
                                 env [key-td map-td])))}
        func-td        (grp.ana.disp/-analyze! env f)
        args-td        (map (partial grp.ana.disp/-analyze! env) args)
        func-args-td   (concat [map-entry-td] args-td)
        update-args-td (concat [map-td key-td func-td] args-td)]
    (if-not (grp.fnt/validate-argtypes!? env
              (grp.art/get-arity (::grp.art/arities func-td) func-args-td)
              func-args-td)
      (let [samples (grp.sampler/try-sampling! env
                      (grp.spec/generator env
                        (get-in (grp.art/get-arity
                                  (::grp.art/arities func-td)
                                  func-args-td)
                          [::grp.art/gspec ::grp.art/return-spec])))]
        (update map-td ::grp.art/samples
          (partial (comp set map)
            (fn [m]
              (let [k (rand-nth (vec (::grp.art/samples key-td)))]
                (assoc m k (rand-nth (vec samples))))))))
      (grp.fnt/analyze-function-call! env update-td update-args-td))))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/update [env sexpr] (analyze-update! env sexpr))

;; CONTEXT: ============ * -> fn ============

(defn analyze-constantly! [env [this-sym value]]
  (let [value-td (grp.ana.disp/-analyze! env value)]
    (-> (get-in (grp.art/external-function-detail env this-sym)
          [::grp.art/arities 1 ::grp.art/gspec ::grp.art/return-spec])
      (merge #::grp.art{:fn-name (gensym "constantly$")
                        :fn-ref  (fn [& _] (rand-nth (vec (::grp.art/samples value-td))))})
      (assoc-in [::grp.art/arities :n ::grp.art/gspec ::grp.art/sampler]
        ::grp.sampler/pure))))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/constantly [env sexpr] (analyze-constantly! env sexpr))

(defn >compose!? [env f g]                                  ;; ~> (comp f g)
  (->> (vals (::grp.art/arities g))
    (map #(grp.sampler/return-sample-gen env (::grp.art/gspec %)))
    (every? (fn [generator]
              (when generator
                (when-let [initial-samples (seq (grp.sampler/try-sampling! env generator
                                                  {::grp.art/original-expression g}))]
                  (let [td {::grp.art/samples (set initial-samples)}]
                    (grp.fnt/validate-argtypes!? env
                      (grp.art/get-arity (::grp.art/arities f) [td])
                      [td]))))))))

(defn analyze-comp! [env [this-sym & fns]]
  (let [comp-td (grp.art/external-function-detail env this-sym)
        fns-td  (map (partial grp.ana.disp/-analyze! env) fns)
        [f & fs] (reverse fns-td)
        valid?  (atom true)]
    (doseq [return-spec (map #(get-in % [::grp.art/gspec ::grp.art/return-spec])
                          (vals (::grp.art/arities f)))
            :let [initial-samples (grp.sampler/try-sampling! env
                                    (grp.spec/generator env return-spec)
                                    {::grp.art/original-expression f})]]
      (loop [td {::grp.art/samples initial-samples}
             [g & gs] fs]
        (when (and @valid? g)
          (if-let [td (when (grp.fnt/validate-argtypes!? env
                              (grp.art/get-arity (::grp.art/arities g) [td])
                              [td])
                        (grp.sampler/sample! env g [td]))]
            (recur td gs)
            (reset! valid? false)))))
    (let [return-gspec (select-keys
                         (get-in (first fns-td) [::grp.art/arities 1 ::grp.art/gspec])
                         [::grp.art/return-spec
                          ::grp.art/return-type
                          ::grp.art/return-predicates])]
      #::grp.art{:fn-name (gensym "comp$")
                 :fn-ref  (apply comp (map (partial grp.sampler/get-fn-ref env) fns-td))
                 :arities (utils/map-vals
                            (fn [arity]
                              (-> arity
                                (update ::grp.art/gspec merge return-gspec)
                                (cond-> (not @valid?)
                                  (assoc-in [::grp.art/gspec ::grp.art/metadata ::grp.sampler/sampler] :default))))
                            (get (last fns-td) ::grp.art/arities))})))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/comp [env sexpr] (analyze-comp! env sexpr))

(defn analyze-complement! [env [this-sym f]]
  (-> (grp.ana.disp/-analyze! env f)
    (update-fn-ref env #(comp not %))
    (update ::grp.art/arities
      (partial utils/map-vals
        #(merge % #::grp.art{:return-spec boolean?
                             :return-type (pr-str boolean?)})))))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/complement [env sexpr] (analyze-complement! env sexpr))

(defn analyze-fnil! [env [this-sym f & nil-patches :as this-expr]]
  (let [fnil-td        (grp.art/external-function-detail env this-sym)
        nil-patches-td (map (partial grp.ana.disp/-analyze! env) nil-patches)
        function       (grp.ana.disp/-analyze! env f)
        args-td        (cons function nil-patches-td)]
    (if-not (grp.fnt/validate-argtypes!? env
              (grp.art/get-arity
                (::grp.art/arities fnil-td)
                args-td)
              args-td)
      {}
      (-> function
        (update-fn-ref env #(apply fnil % (mapv (comp rand-nth vec ::grp.art/samples) nil-patches-td)))
        (update ::grp.art/arities
          (partial utils/map-vals
            (fn [arity]
              (-> arity
                (update ::grp.art/gspec
                  (fn [gspec]
                    (-> gspec
                      (update ::grp.art/argument-specs
                        (fn [arg-specs]
                          (mapv (fn [spec patch]
                                  (if (= ::not-found patch) spec
                                                            #(or (nil? %) (grp.spec/valid? env spec %))))
                            arg-specs (concat nil-patches (repeat ::not-found)))))
                      (update ::grp.art/argument-types
                        (fn [arg-specs]
                          (mapv (fn [-type patch]
                                  (if (= ::not-found patch) -type
                                                            (str "(or nil? " -type ")")))
                            arg-specs (concat nil-patches (repeat ::not-found))))))))
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
                          args (concat
                                 (map (comp rand-nth vec ::grp.art/samples) nil-patches-td)
                                 (repeat nil)))))
                    (partial grp.fnt/validate-arguments-predicate!? env arity)))))))))))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/fnil [env sexpr] (analyze-fnil! env sexpr))

(defn analyze-juxt! [env [this-sym & fns]]
  (let [juxt-td (grp.ana.disp/-analyze! env this-sym)
        fns-td  (map (partial grp.ana.disp/-analyze! env) fns)
        fn-ref  (fn [& args]
                  (-> juxt
                    (apply (map (partial grp.sampler/get-fn-ref env) fns-td))
                    (apply args)))]
    {}))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/juxt [env sexpr] (analyze-juxt! env sexpr))

(defn >partial! [env [_ f & args] function args-td]
  (let [ptd (update function ::grp.art/arities
              (partial utils/filter-vals
                #(grp.fnt/valid-argtypes? env % args-td)))]
    (if (seq (::grp.art/arities ptd))
      (update ptd ::grp.fnt/partial-argtypes concat args-td)
      (do (grp.art/record-error! env args :error/invalid-function-arguments {:function f})
          {}))))

(defn analyze-partial! [env [this-sym f & values :as sexpr]]
  (let [partial-td (grp.art/external-function-detail env this-sym)
        values-td  (mapv (partial grp.ana.disp/-analyze! env) values)
        function   (grp.ana.disp/-analyze! env f)]
    (if (grp.fnt/validate-argtypes!? env
          (grp.art/get-arity
            (::grp.art/arities partial-td)
            (cons function values-td))
          (cons function values-td))
      (>partial! env sexpr function values-td)
      {})))

(defmethod grp.ana.disp/analyze-mm 'clojure.core/partial [env sexpr] (analyze-partial! env sexpr))
