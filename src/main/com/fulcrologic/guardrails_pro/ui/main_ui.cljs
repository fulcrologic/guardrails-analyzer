(ns com.fulcrologic.guardrails-pro.ui.main-ui
  (:require
    [com.fulcrologic.guardrails-pro.ui.root :as root]
    [com.fulcrologic.fulcro.application :as app]))

(defonce app (app/fulcro-app))

(defn reload []
  (app/mount! app root/Root "checker"))

(defn ^:export init []
  (let [body js/document.body
        div  (.createElement js/document "div")]
    (set! (.-id div) "checker")
    (.appendChild body div))
  (app/mount! app root/Root "checker"))
