(ns com.fulcrologic.guardrails-pro.daemon.server.problems
  (:require
    [clojure.set :as set]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]
    [com.fulcrologic.guardrails-pro.static.clojure-reader :as clj-reader]
    [taoensso.timbre :as log]))

(defonce problems (atom {}))

(defn get!
  ([] @problems)
  ([file]
   (or (when-let [file-ns (clj-reader/read-ns-decl file)]
         (log/debug "get! problems for:" file-ns)
         (into {}
           (filter (fn [[fn-sym _]]
                     (= (namespace fn-sym)
                       (str file-ns))))
           (get!)))
     {})))

(defn set! [new-problems]
  (reset! problems new-problems))

(defn clear! []
  (reset! problems {}))

(defmulti format-for-mm (fn [editor _problems] editor))

(defmethod format-for-mm :default [_ problems]
  problems)

(defmethod format-for-mm "vim" [_ problems]
  (mapcat
    (fn [[_fn-sym {::grp.art/keys [errors warnings]}]]
      (let [vim-remap #::grp.art{:message      "text"
                                 :line-number  "lnum"
                                 :column-start "col"
                                 :column-end   "end_col"}
            format-problem (fn [problem]
                             (-> problem
                               (update ::grp.art/column-end dec)
                               (set/rename-keys vim-remap)
                               (select-keys (vals vim-remap))))
            error #(assoc % "type" "E")
            warning #(assoc % "type" "W")]
        (concat
          (map (comp error format-problem) errors)
          (map (comp warning format-problem) warnings))))
    problems))

(defn format-for [editor problems]
  (format-for-mm editor problems))
