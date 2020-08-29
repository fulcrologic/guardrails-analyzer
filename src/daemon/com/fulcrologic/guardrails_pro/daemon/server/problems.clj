(ns com.fulcrologic.guardrails-pro.daemon.server.problems
  (:require
    [clojure.set :as set]
    [com.fulcrologic.guardrails-pro.runtime.artifacts :as grp.art]))

(defonce problems (atom {}))

(defn get!
  ([] @problems)
  ([_file] (get!))) ;; TODO

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
                                 :column-start "col"
                                 :line         "lnum"}
            format-problem (fn [problem]
                             (-> problem
                               (merge #::grp.art{:line 1}) ;FIXME
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
