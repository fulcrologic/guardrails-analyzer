(ns com.fulcrologic.copilot.analysis.analyzer.hofs
  (:require
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as cp.ana.disp]
    [com.fulcrologic.copilot.analysis.analyzer.literals]
    [com.fulcrologic.copilot.analysis.function-type :as cp.fnt]
    [com.fulcrologic.copilot.analysis.sampler :as cp.sampler]
    [com.fulcrologic.copilot.analysis.spec :as cp.spec]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologic.guardrails.core :as gr]
    [com.fulcrologic.guardrails.utils :as utils]
    [com.fulcrologicpro.com.rpl.specter :as $]))

;; TODO potential duplication with >defn
(defn analyze-single-arity! [env [arglist gspec & body]]
  (let [gspec  (cp.fnt/interpret-gspec env arglist gspec)
        env    (cp.fnt/bind-argument-types env arglist gspec)
        result (cp.ana.disp/analyze-statements! env body)]
    (cp.fnt/check-return-type! env gspec result)))

(defn location-of-lambda [lambda]
  ((juxt :line :column) (meta (first lambda))))

(defn analyze-lambda! [env lambda]
  (let [{::cp.art/keys [lambdas]} (cp.art/function-detail env (::cp.art/checking-sym env))
        lambda-td (get lambdas (location-of-lambda lambda) {})
        arities   (drop-while (complement vector?) lambda)]
    (if (vector? (first arities))
      (analyze-single-arity! env arities)
      (doseq [arity arities]
        (analyze-single-arity! env arity)))
    lambda-td))

(defmethod cp.ana.disp/analyze-mm `gr/>fn [env sexpr] (analyze-lambda! env sexpr))

(defn update-fn-ref [{:as function ::cp.art/keys [fn-ref env->fn]} env f]
  (cond
    fn-ref (update function ::cp.art/fn-ref f)
    env->fn (assoc function ::cp.art/env->fn
                            (f (env->fn env)))
    :else function))

(defn >fn-ret [td]
  ($/transform [($/walker ::cp.art/gspec) ::cp.art/gspec]
    #(select-keys %
       [::cp.art/return-spec
        ::cp.art/return-type
        ::cp.art/return-predicates])
    (select-keys td [::cp.art/arities])))

(defn >fn-args [td]
  ($/transform [($/walker ::cp.art/gspec) ::cp.art/gspec]
    #(select-keys %
       [::cp.art/argument-specs
        ::cp.art/argument-types
        ::cp.art/argument-predicates])
    (select-keys td [::cp.art/arities])))

;; CONTEXT: ============ fn * -> val ============

(defn analyze-apply! [env [this-sym f & args]]
  (let [apply-td      (cp.art/external-function-detail env this-sym)
        function      (cp.ana.disp/-analyze! env f)
        [args-td args-coll-td] ((juxt drop-last last)
                                (map (partial cp.ana.disp/-analyze! env) args))
        fn-args-td    (conj (vec args-td) args-coll-td)
        apply-args-td (cons function fn-args-td)]
    (if-not (cp.fnt/validate-argtypes!? env
              (cp.art/get-arity
                (::cp.art/arities apply-td)
                apply-args-td)
              apply-args-td)
      {::cp.art/samples (cp.sampler/try-sampling! env
                           (cp.spec/generator env
                             (get-in (cp.art/get-arity
                                       (::cp.art/arities function)
                                       fn-args-td)
                               [::cp.art/gspec ::cp.art/return-spec]))
                           {::cp.art/original-expression function})}
      (cp.fnt/analyze-function-call! env function
        (concat args-td
          (apply map #(hash-map ::cp.art/samples #{%})
            (::cp.art/samples args-coll-td)))))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/apply [env sexpr] (analyze-apply! env sexpr))

(defn analyze-map-like! [env [this-sym f & colls]]
  (let [map-td   (cp.art/external-function-detail env this-sym)
        func-td  (cp.ana.disp/-analyze! env f)
        colls-td (map (partial cp.ana.disp/-analyze! env) colls)]
    (cp.fnt/analyze-function-call! env map-td (cons func-td colls-td))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/map [env sexpr] (analyze-map-like! env sexpr))

(defn analyze-reduce-like! [env [this-sym f init coll]]
  (let [reduce-td (cp.art/external-function-detail env this-sym)
        func-td   (cp.ana.disp/-analyze! env f)
        init-td   (cp.ana.disp/-analyze! env init)
        coll-td   (cp.ana.disp/-analyze! env coll)]
    (cp.fnt/analyze-function-call! env reduce-td [func-td init-td coll-td])))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/reduce [env sexpr] (analyze-reduce-like! env sexpr))

(defn analyze-some! [env [this-sym pred coll]]
  (let [some-td (cp.art/external-function-detail env this-sym)
        pred-td (cp.ana.disp/-analyze! env pred)
        coll-td (cp.ana.disp/-analyze! env coll)]
    (if-not (cp.fnt/validate-argtypes!? env
              (cp.art/get-arity (::cp.art/arities some-td) [pred-td coll-td])
              [pred-td coll-td])
      {::cp.art/samples (cp.sampler/try-sampling! env
                           (cp.spec/generator env
                             (get-in (cp.art/get-arity
                                       (::cp.art/arities some-td)
                                       [pred-td coll-td])
                               [::cp.art/gspec ::cp.art/return-spec])))}
      ;; TODO: validate that pred accepts coll elements
      (cp.fnt/analyze-function-call! env some-td [pred-td coll-td]))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/some [env sexpr] (analyze-some! env sexpr))

(defn analyze-split-with! [env [this-sym pred coll]]
  (let [split-with-td (cp.art/external-function-detail env this-sym)
        pred-td       (cp.ana.disp/-analyze! env pred)
        coll-td       (cp.ana.disp/-analyze! env coll)]
    (if-not (cp.fnt/validate-argtypes!? env
              (cp.art/get-arity (::cp.art/arities split-with-td) [pred-td coll-td])
              [pred-td coll-td])
      {::cp.art/samples (cp.sampler/try-sampling! env
                           (cp.spec/generator env
                             (get-in (cp.art/get-arity
                                       (::cp.art/arities split-with-td)
                                       [pred-td coll-td])
                               [::cp.art/gspec ::cp.art/return-spec])))}
      ;; TODO: validate that pred accepts coll elements
      (cp.fnt/analyze-function-call! env split-with-td [pred-td coll-td]))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/split-with [env sexpr] (analyze-split-with! env sexpr))

(defn analyze-swap! [env [this-sym a f & args]]
  (let [swap-td      (cp.art/external-function-detail env this-sym)
        atom-td      (cp.ana.disp/-analyze! env a)
        func-td      (cp.ana.disp/-analyze! env f)
        args-td      (map (partial cp.ana.disp/-analyze! env) args)
        swap-args-td (concat [atom-td func-td] args-td)]
    (if-not (cp.fnt/validate-argtypes!? env
              (cp.art/get-arity (::cp.art/arities swap-td) swap-args-td)
              swap-args-td)
      {::cp.art/samples (cp.sampler/try-sampling! env
                           (cp.spec/generator env
                             (get-in (cp.art/get-arity
                                       (::cp.art/arities func-td)
                                       (cons {} args-td))
                               [::cp.art/gspec ::cp.art/return-spec])))}
      (let [func-arg-td {::cp.art/samples
                         (cp.sampler/try-sampling! env
                           (cp.spec/generator env
                             (get-in (cp.art/get-arity
                                       (::cp.art/arities func-td)
                                       (cons {} args-td))
                               [::cp.art/gspec ::cp.art/argument-specs 0])))}]
        (cp.fnt/analyze-function-call! env func-td (cons func-arg-td args-td))))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/swap! [env sexpr] (analyze-swap! env sexpr))

(defn analyze-update! [env [this-sym m k f & args]]
  (let [update-td      (cp.art/external-function-detail env this-sym)
        map-td         (cp.ana.disp/-analyze! env m)
        key-td         (cp.ana.disp/-analyze! env k)
        map-entry-td   {::cp.art/samples
                        (set (map (fn [[k m]] (k m))
                               (cp.sampler/random-samples-from-each
                                 env [key-td map-td])))}
        func-td        (cp.ana.disp/-analyze! env f)
        args-td        (map (partial cp.ana.disp/-analyze! env) args)
        func-args-td   (concat [map-entry-td] args-td)
        update-args-td (concat [map-td key-td func-td] args-td)]
    (if-not (cp.fnt/validate-argtypes!? env
              (cp.art/get-arity (::cp.art/arities func-td) func-args-td)
              func-args-td)
      (let [samples (cp.sampler/try-sampling! env
                      (cp.spec/generator env
                        (get-in (cp.art/get-arity
                                  (::cp.art/arities func-td)
                                  func-args-td)
                          [::cp.art/gspec ::cp.art/return-spec])))]
        (update map-td ::cp.art/samples
          (partial (comp set map)
            (fn [m]
              (let [k (rand-nth (vec (::cp.art/samples key-td)))]
                (assoc m k (rand-nth (vec samples))))))))
      (cp.fnt/analyze-function-call! env update-td update-args-td))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/update [env sexpr] (analyze-update! env sexpr))

;; CONTEXT: ============ * -> fn ============

(defn analyze-constantly! [env [this-sym value]]
  (let [value-td (cp.ana.disp/-analyze! env value)]
    (-> (get-in (cp.art/external-function-detail env this-sym)
          [::cp.art/arities 1 ::cp.art/gspec ::cp.art/return-spec])
      (merge #::cp.art{:fn-name (gensym "constantly$")
                        :fn-ref  (fn [& _] (rand-nth (vec (::cp.art/samples value-td))))})
      (assoc-in [::cp.art/arities :n ::cp.art/gspec ::cp.art/sampler]
        ::cp.sampler/pure))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/constantly [env sexpr] (analyze-constantly! env sexpr))

(defn >compose!? [env f g]                                  ;; ~> (comp f g)
  (->> (vals (::cp.art/arities g))
    (map #(cp.sampler/return-sample-gen env (::cp.art/gspec %)))
    (every? (fn [generator]
              (when generator
                (when-let [initial-samples (seq (cp.sampler/try-sampling! env generator
                                                  {::cp.art/original-expression g}))]
                  (let [td {::cp.art/samples (set initial-samples)}]
                    (cp.fnt/validate-argtypes!? env
                      (cp.art/get-arity (::cp.art/arities f) [td])
                      [td]))))))))

(defn analyze-comp! [env [this-sym & fns]]
  (let [comp-td (cp.art/external-function-detail env this-sym)
        fns-td  (map (partial cp.ana.disp/-analyze! env) fns)
        [f & fs] (reverse fns-td)
        valid?  (atom true)]
    (doseq [return-spec (map #(get-in % [::cp.art/gspec ::cp.art/return-spec])
                          (vals (::cp.art/arities f)))
            :let [initial-samples (cp.sampler/try-sampling! env
                                    (cp.spec/generator env return-spec)
                                    {::cp.art/original-expression f})]]
      (loop [td {::cp.art/samples initial-samples}
             [g & gs] fs]
        (when (and @valid? g)
          (if-let [td (when (cp.fnt/validate-argtypes!? env
                              (cp.art/get-arity (::cp.art/arities g) [td])
                              [td])
                        (cp.sampler/sample! env g [td]))]
            (recur td gs)
            (reset! valid? false)))))
    (let [return-gspec (select-keys
                         (get-in (first fns-td) [::cp.art/arities 1 ::cp.art/gspec])
                         [::cp.art/return-spec
                          ::cp.art/return-type
                          ::cp.art/return-predicates])]
      #::cp.art{:fn-name (gensym "comp$")
                 :fn-ref  (apply comp (map (partial cp.sampler/get-fn-ref env) fns-td))
                 :arities (utils/map-vals
                            (fn [arity]
                              (-> arity
                                (update ::cp.art/gspec merge return-gspec)
                                (cond-> (not @valid?)
                                  (assoc-in [::cp.art/gspec ::cp.art/metadata ::cp.sampler/sampler] :default))))
                            (get (last fns-td) ::cp.art/arities))})))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/comp [env sexpr] (analyze-comp! env sexpr))

(defn analyze-complement! [env [this-sym f]]
  (-> (cp.ana.disp/-analyze! env f)
    (update-fn-ref env #(comp not %))
    (update ::cp.art/arities
      (partial utils/map-vals
        #(merge % #::cp.art{:return-spec boolean?
                             :return-type (pr-str boolean?)})))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/complement [env sexpr] (analyze-complement! env sexpr))

(defn analyze-fnil! [env [this-sym f & nil-patches :as this-expr]]
  (let [fnil-td        (cp.art/external-function-detail env this-sym)
        nil-patches-td (map (partial cp.ana.disp/-analyze! env) nil-patches)
        function       (cp.ana.disp/-analyze! env f)
        args-td        (cons function nil-patches-td)]
    (if-not (cp.fnt/validate-argtypes!? env
              (cp.art/get-arity
                (::cp.art/arities fnil-td)
                args-td)
              args-td)
      {}
      (-> function
        (update-fn-ref env #(apply fnil % (mapv (comp rand-nth vec ::cp.art/samples) nil-patches-td)))
        (update ::cp.art/arities
          (partial utils/map-vals
            (fn [arity]
              (-> arity
                (update ::cp.art/gspec
                  (fn [gspec]
                    (-> gspec
                      (update ::cp.art/argument-specs
                        (fn [arg-specs]
                          (mapv (fn [spec patch]
                                  (if (= ::not-found patch) spec
                                                            #(or (nil? %) (cp.spec/valid? env spec %))))
                            arg-specs (concat nil-patches (repeat ::not-found)))))
                      (update ::cp.art/argument-types
                        (fn [arg-specs]
                          (mapv (fn [-type patch]
                                  (if (= ::not-found patch) -type
                                                            (str "(or nil? " -type ")")))
                            arg-specs (concat nil-patches (repeat ::not-found))))))))
                (as-> arity
                  (update-in arity [::cp.art/gspec ::cp.art/argument-predicates]
                    (fnil conj [])
                    (fn [& args]
                      (cp.fnt/validate-argtypes!? env arity
                        (map (fn [arg patch]
                               (let [value (if (some? arg) arg patch)]
                                 (hash-map
                                   ::cp.art/samples #{value}
                                   ::cp.art/original-expression value)))
                          args (concat
                                 (map (comp rand-nth vec ::cp.art/samples) nil-patches-td)
                                 (repeat nil)))))
                    (partial cp.fnt/validate-arguments-predicate!? env arity)))))))))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/fnil [env sexpr] (analyze-fnil! env sexpr))

(defn analyze-juxt! [env [this-sym & fns]]
  (let [juxt-td (cp.ana.disp/-analyze! env this-sym)
        fns-td  (map (partial cp.ana.disp/-analyze! env) fns)
        fn-ref  (fn [& args]
                  (-> juxt
                    (apply (map (partial cp.sampler/get-fn-ref env) fns-td))
                    (apply args)))]
    {}))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/juxt [env sexpr] (analyze-juxt! env sexpr))

(defn >partial! [env [_ f & args :as sexpr] function args-td]
  (let [ptd (update function ::cp.art/arities
              (partial utils/filter-vals
                #(cp.fnt/valid-argtypes? env % args-td)))]
    (if (seq (::cp.art/arities ptd))
      (update ptd ::cp.fnt/partial-argtypes concat args-td)
      (do (cp.art/record-error! env args :error/invalid-partially-applied-arguments
            {:function (str (cp.art/unwrap-meta f))})
        (cp.ana.disp/unknown-expr env sexpr)))))

(defn analyze-partial! [env [this-sym f & values :as sexpr]]
  (let [partial-td (cp.art/external-function-detail env this-sym)
        values-td  (mapv (partial cp.ana.disp/-analyze! env) values)
        function   (cp.ana.disp/-analyze! env f)]
    (if (cp.fnt/validate-argtypes!? env
          (cp.art/get-arity
            (::cp.art/arities partial-td)
            (cons function values-td))
          (cons function values-td))
      (>partial! env sexpr function values-td)
      (cp.ana.disp/unknown-expr env f))))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/partial [env sexpr]
  (analyze-partial! env sexpr))
