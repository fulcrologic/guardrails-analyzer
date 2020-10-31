(ns com.fulcrologic.guardrails-pro.checker
  (:require
    com.fulcrologic.guardrails-pro.ftags.clojure-core
    [clojure.pprint :refer [pprint]]
    [clojure.test.check.generators]
    [com.fulcrologic.guardrails-pro.analysis.analyzer :as grp.ana]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.forms :as grp.forms]
    [com.fulcrologic.guardrails-pro.ui.problem-formatter :refer [format-problems]]
    [com.rpl.specter :as $]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]
    [taoensso.tufte :as prof :refer [profile p]]))

(defn check-form! [env form]
  (profile {}
    (try (p ::check-form! (grp.ana/analyze! env form))
      (catch #?(:clj Throwable :cljs :default) t
        (grp.art/record-error! env form :error/failed-to-analyze-form)
        (log/error t "Failed to analyze form:" form)))))

(defn check!
  ([msg cb] (check! (grp.art/build-env) msg cb))
  ([env {:as msg :keys [forms file NS]} cb]
   (let [env (-> env
               (assoc ::grp.art/checking-file file)
               (assoc ::grp.art/current-ns NS))]
     (grp.art/clear-problems! file)
     (grp.spec/with-cache {}
       #?(:cljs (fn check-forms! [[form & forms]]
                  (if-not form (cb)
                    (js/setTimeout
                      (fn []
                        (check-form! env form)
                        (check-forms! forms))
                      100)))
          :clj (fn [forms]
                 (doseq [form forms]
                   (check-form! env form))
                 (cb)))
       (grp.forms/interpret forms)))))

(defonce to-check (atom nil))

(defn prepare-check! [msg cb]
  (reset! to-check [msg cb]))

(defn run-prepared-check! []
  (apply check! @to-check)
  (reset! to-check nil))

(defn- transit-safe-problems [problems]
  ($/transform ($/walker ::grp.art/problem-type)
    #(-> %
       (dissoc ::grp.art/actual ::grp.art/expected ::grp.art/original-expression)
       (assoc ::grp.art/expression (str (::grp.art/original-expression %))))
    problems))

(defn- formatted-bindings [bindings]
  (enc/map-vals
    (fn [{::grp.art/keys [type samples original-expression]}]
      (let [pp-samples (mapv (fn [s] (with-out-str (pprint s))) samples)]
        {:type       type
         :expression (pr-str original-expression)
         :samples    pp-samples}))
    bindings))

(defn gather-analysis! []
  {:problems (-> @grp.art/problems format-problems transit-safe-problems)
   :bindings (formatted-bindings @grp.art/binding-annotations)})
