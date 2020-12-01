(ns com.fulcrologic.copilot.ui.binding-formatter
  (:require
    #?@(:cljs [[goog.string :refer [format]]
               [goog.string.format]])
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [com.fulcrologic.copilot.artifacts :as grp.art]
    [com.fulcrologicpro.com.rpl.specter :as $]))

(defn html-escape [s]
  (-> s
    (str/replace "&" "&amp;")
    (str/replace "<" "&lt;")
    (str/replace ">" "&gt;")))

(defn format-binding [bind]
  (-> bind
    (assoc ::grp.art/message
      (str "Bindings for: " (::grp.art/original-expression bind)))
    (assoc ::grp.art/tooltip
      (format
        "<b>Type:</b>%s<br><b>Sample Values:</b><br>%s"
        (some-> bind ::grp.art/type html-escape)
        (str/join
          (mapv (comp #(format "<pre>%s</pre>" (html-escape %))
                  #(str/trim (with-out-str (pprint %))))
            (::grp.art/samples bind)))))))

(defn format-bindings [bindings]
  ($/transform [($/walker ::grp.art/samples)] format-binding bindings))
