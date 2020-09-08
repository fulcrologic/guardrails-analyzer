(ns com.fulcrologic.guardrails-pro.parser
  "Implementation of reading >defn for macro expansion."
  (:require
    [clojure.set :as set]
    [clojure.walk :as walk]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.forms :as forms]
    [taoensso.encore :as enc])
  (:import
    (clojure.lang Cons)))

(def gen? #{:gen '<-})
(def ret? #{:ret '=>})
(def such-that? #{:st '|})

(def append (fnil conj []))

(defn init-parser-state
  ([args] (init-parser-state args {}))
  ([args opts] (init-parser-state {} args opts))
  ([result args opts]
   {::result result
    ::args   args
    ::opts   opts}))

(defn next-args
  ([state] (next-args state next))
  ([state next-fn]
   (update state ::args next-fn)))

(defn update-result [state f & args]
  (update state ::result (partial apply f) args))

(defn sym-meta
  [{:as state [sym] ::args}]
  (assoc state ::fn-meta (meta sym)))

(defn var-name
  [{:as state [nm] ::args}]
  (if (qualified-symbol? nm)
    (-> state
      (sym-meta)
      (next-args))
    (throw (ex-info (format "%s is not fully qualified symbol" nm) {}))))

(defn function-name
  [{:as state, [nm] ::args
    , {:keys [optional-fn-name?
              record-fn-name?]} ::opts}]
  (if (simple-symbol? nm)
    (-> state
      (cond-> record-fn-name?
        (update-result assoc ::grp.art/name nm))
      (sym-meta)
      (next-args))
    (if optional-fn-name? state
      (throw (ex-info (format "%s is not a simple symbol, therefore is an invalid function name." nm) state)))))

(defn optional-docstring
  [{:as state, [candidate] ::args}]
  (cond-> state
    (string? candidate)
    (next-args)))

(defn gspec-metadata
  [{:as state, gspec ::args}]
  (let [metadata (some->> (merge (::fn-meta state) (meta gspec))
                   (enc/remove-keys #{:file :line :end-line :column :end-column}))]
    (cond-> state (some? metadata)
      (update-result assoc ::grp.art/metadata metadata))))

;; NOTE: needs better name
(defn loop-over-args
  [state done? result-fn]
  (loop [{:as state, [arg] ::args} state]
    (cond
      (done? arg) state
      (not arg) (throw (ex-info (format "syntax error: expected %s" done?) state))
      :else (recur (-> state (update-result result-fn arg) next-args)))))

(defn arg-specs
  [{:as state, ::keys [args]}]
  (loop-over-args state
    (set/union ret? such-that?)
    (fn [result arg]
      (-> result
        (update ::grp.art/arg-types append (pr-str arg))
        (update ::grp.art/arg-specs append arg)))))

(defn replace-arglist [lambda new-arglist]
  (apply list (first lambda) new-arglist (rest (rest lambda))))

(defn arg-predicates
  [{:as state, [lookahead & remainder] ::args} arglist]
  (if (such-that? lookahead)
    (-> state
      (next-args)
      (loop-over-args ret?
        (fn [result arg]
          (update result ::grp.art/arg-predicates
            append (replace-arglist arg arglist)))))
    state))

(defn return-type
  [{:as state, [lookahead return-spec] ::args}]
  (if (ret? lookahead)
    (-> state
      (next-args nnext)
      (update-result assoc ::grp.art/return-spec return-spec)
      (update-result assoc ::grp.art/return-type (pr-str return-spec)))
    (throw (ex-info "Syntax error: expected a return type!" state))))

(defn such-that
  [{:as state, ::keys [result], [lookahead & remainder :as args] ::args}]
  (if (such-that? lookahead)
    (-> state
      (next-args)
      (loop-over-args (some-fn not gen?)
        (fn [result arg]
          (update result ::grp.art/return-predicates append arg))))
    state))

(defn generator
  [{:as state, [lookahead & remainder] ::args}]
  (cond-> state (gen? lookahead)
    (->
      (next-args nnext)
      (update-result assoc ::grp.art/generator (first remainder)))))

(defn gspec-parser
  [{:as state, gspec ::args} arglist]
  (-> state
    (gspec-metadata)
    (arg-specs)
    (arg-predicates arglist)
    (return-type)
    (such-that)
    (generator)))

(defn arity-body? [b] (or (instance? Cons b) (list? b)))

(defn body-arity [arglist]
  (if (contains? (set arglist) '&)
    :n (count arglist)))


(comment
  (>defn g ...)
  `(register! `g
     {::arities       ...
      :extern-symbols ...
      :lambdas        {'*gennm {::arities ...
                                :env->fn  #(let [a (from-env 'a %)]
                                             (fn [x] ^:pure [int? => string?]
                                               (str/includes? a x)))}}
      :body           '(let [s (map (>fn *gennm [x] [(s/keys :req [:person/name])
                                                     => string?]) [1 2 3])]
                         s)}))

(defn lambda:env->fn:impl [binds fn-form]
  (let [env (gensym "env$")]
    `(fn [~env]
       (let [~@(mapcat (fn [sym] [sym `(get ~env '~sym)]) binds)]
         ~fn-form))))

(defmacro lambda:env->fn [binds fn-form]
  (lambda:env->fn:impl binds fn-form))

(defn record-symbols [body]
  (let [symbols-map (atom [])]
    (walk/postwalk (fn [f]
                     (when (symbol? f)
                       (swap! symbols-map conj f)))
      body)
    @symbols-map))

(declare parse-fn)

(defn lambda-name [nm]
  (let [gen (gensym ">fn$")]
    (if-not nm gen
      (symbol (str nm "$$" (name gen))))))

(defn parse-body-for-lambdas
  [{:as state, body ::args}]
  (let [lambdas (atom {})]
    (-> (walk/prewalk
          (fn [x]
            (if (and (seq? x) (= '>fn (first x)))
              (let [function (parse-fn (rest x))
                    binds (record-symbols x)
                    fn-name (lambda-name (::grp.art/name function))]
                (swap! lambdas assoc
                  `(quote ~fn-name)
                  (merge function
                    #::grp.art{:env->fn (lambda:env->fn:impl binds x)}))
                (if (::grp.art/name function)
                  (list* '>fn fn-name (rest (next x)))
                  (list* '>fn fn-name (rest x))))
              x))
          state)
      (update-result assoc ::grp.art/lambdas @lambdas))))

(defn single-arity
  [{:as state, {:keys [assert-no-body?]} ::opts
    , [arglist gspec & forms :as args] ::args}]
  (if (and (seq forms) assert-no-body?)
    (throw (ex-info "Syntax error: function body not expected!" state))
    (-> state
      (update-result assoc-in [::grp.art/arities (body-arity arglist)]
        (cond-> {::grp.art/arglist (forms/form-expression arglist)
                 ::grp.art/gspec   (-> state
                                     (assoc ::args gspec)
                                     (gspec-parser arglist)
                                     ::result)}
          (seq forms)
          (-> (assoc ::grp.art/body (forms/form-expression (vec forms)))
            (with-meta {::grp.art/raw-body `(quote ~(vec forms))}))))
      (next-args nnext)
      (parse-body-for-lambdas))))

(defn multiple-arities
  [{:as state, ::keys [result args]}]
  (reduce
    (fn [state arity-body]
      (when-not (arity-body? arity-body)
        (throw (ex-info "Syntax error: multi-arity function body expected!" state)))
      (-> state
        (assoc ::args arity-body)
        (single-arity)))
    state args))

(defn function-content
  [{:as state [lookahead] ::args}]
  (if (arity-body? lookahead)
    (multiple-arities state)
    (single-arity state)))

(defn parse-defn
  "Takes the body of a defn and returns parsed arities and top level location information."
  [[defn-sym :as args]]
  (-> (init-parser-state args)
    (function-name)
    (optional-docstring)
    (function-content)
    ::result
    (assoc ::grp.art/location (grp.art/new-location (meta defn-sym)))))

(defn parse-fdef [args]
  (-> (init-parser-state args {:assert-no-body? true})
    (var-name)
    (function-content)
    ::result))

(defn parse-fn [args]
  (-> (init-parser-state args
        {:optional-fn-name? true
         :record-fn-name?   true})
    (function-name)
    (function-content)
    ::result))

(defn parse-fspec [args]
  (when (symbol? (first args))
    (throw (ex-info "Should not contain a function name, expected an arglist!" {})))
  (-> (init-parser-state args {:assert-no-body? true})
    (function-content)
    ::result))
