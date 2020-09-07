(ns com.fulcrologic.guardrails-pro.parser
  "Implementation of reading >defn for macro expansion."
  (:require
    [clojure.set :as set]
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
  [{:as state, [nm] ::args {:keys [optional-fn-name?]} ::opts}]
  (if (simple-symbol? nm)
    (-> state
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

(defn single-arity
  [{:as state , ::keys [result]
    , {:keys [assert-no-body?]} ::opts
    , [arglist gspec & forms :as args] ::args}]
  (if (and (seq forms) assert-no-body?)
    (throw (ex-info "Syntax error: function body not expected!" state))
    (update-result state assoc (body-arity arglist)
      (cond-> {::grp.art/arglist (forms/form-expression arglist)
               ::grp.art/gspec   (-> state
                                   (assoc ::args gspec)
                                   (gspec-parser arglist)
                                   ::result)}
        (seq forms)
        (-> (assoc ::grp.art/body (forms/form-expression (vec forms)))
          (with-meta {::grp.art/raw-body `(quote ~(vec forms))}))))))

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
  (let [arities (-> (init-parser-state args)
                  (function-name)
                  (optional-docstring)
                  (function-content)
                  ::result)]
    {::grp.art/arities  arities
     ::grp.art/location (grp.art/new-location (meta defn-sym))}))

(defn parse-fn [args]
  (-> (init-parser-state args {:optional-fn-name? true})
    (function-name)
    (function-content)
    ::result))

(defn parse-fdef [args]
  (-> (init-parser-state args {:assert-no-body? true})
    (var-name)
    (function-content)
    ::result))

(defn parse-fspec [args]
  (when (symbol? (first args))
    (throw (ex-info "Should not contain a function name, expected an arglist!" {})))
  (-> (init-parser-state args {:assert-no-body? true})
    (function-content)
    ::result))
