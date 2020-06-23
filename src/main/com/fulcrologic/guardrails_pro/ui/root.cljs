(ns com.fulcrologic.guardrails-pro.ui.root
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]))

(defsc Root [this {:keys [:id] :as props}]
  {:query         [:id]
   :initial-state {:id 1}}
  (dom/div "TODO"))
