(ns com.fulcrologic.copilot.daemon.ui.root
  (:require
    [com.fulcrologicpro.fulcro.components :as f.c :refer [defsc]]
    [com.fulcrologicpro.fulcro.dom :as dom]
    [com.fulcrologic.copilot.artifacts :as cp.art]))

(defn ui-error-part [[part-key part]]
  (dom/tr {:key (name part-key)}
    (dom/th (name part-key))
    (dom/td (str part))))

(defn ui-error [error]
  (dom/table {:key (hash error)}
    (dom/tbody
      (map ui-error-part error))))

(defn ui-problems [all-problems]
  (dom/ul
    (map (fn [[fn-sym {::cp.art/keys [errors]}]]
           (dom/li {:key (pr-str fn-sym)}
             (dom/h3 (pr-str fn-sym))
             (dom/ul
               (map ui-error errors))))
      all-problems)))

(defsc Root [_this {:keys [all-problems]}]
  {:query         [:all-problems]
   :initial-state {:all-problems {:fake :problem}}}
  (dom/div
    (dom/h2 "PROBLEMS:")
    (ui-problems all-problems)))
