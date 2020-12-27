Copyright (c) Fulcrologic, LLC. All rights reserved.

Permission to use this software requires that you
agree to our End-user License Agreement, legally obtain a license,
and use this software within the constraints of the terms specified
by said license.

You may NOT publish, redistribute, or reproduce this software or its source
code in any form (printed, electronic, or otherwise) except as explicitly
allowed by your license agreement..

(ns com.fulcrologic.copilot.ui.binding-formatter
  (:require
    #?@(:cljs [[goog.string :refer [format]]
               [goog.string.format]])
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologicpro.com.rpl.specter :as $]))

(defn html-escape [s]
  (-> s
    (str/replace "&" "&amp;")
    (str/replace "<" "&lt;")
    (str/replace ">" "&gt;")))

(defn format-binding [bind]
  (-> bind
    (assoc ::cp.art/message
      (str "Bindings for: " (::cp.art/original-expression bind)))
    (assoc ::cp.art/tooltip
      (format
        "<b>Type:</b>%s<br><b>Sample Values:</b><br>%s"
        (some-> bind ::cp.art/type html-escape)
        (str/join
          (mapv (comp #(format "<pre>%s</pre>" (html-escape %))
                  #(str/trim (with-out-str (pprint %))))
            (::cp.art/samples bind)))))))

(defn format-bindings [bindings]
  ($/transform [($/walker ::cp.art/samples)] format-binding bindings))
