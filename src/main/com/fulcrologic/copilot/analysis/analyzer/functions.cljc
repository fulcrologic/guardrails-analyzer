;; Copyright (c) Fulcrologic, LLC. All rights reserved.
;;
;; Permission to use this software requires that you
;; agree to our End-user License Agreement, legally obtain a license,
;; and use this software within the constraints of the terms specified
;; by said license.
;;
;; You may NOT publish, redistribute, or reproduce this software or its source
;; code in any form (printed, electronic, or otherwise) except as explicitly
;; allowed by your license agreement..

(ns com.fulcrologic.copilot.analysis.analyzer.functions
  (:require
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as cp.ana.disp]
    [com.fulcrologic.copilot.analysis.function-type :as cp.fnt]
    [com.fulcrologic.copilot.analysis.sampler :as cp.sampler]
    [com.fulcrologic.copilot.analysis.spec :as cp.spec]
    [com.fulcrologic.copilot.artifacts :as cp.art]))

(defn analyze:get-in [env [this-sym m path & [dflt]]]
  (let [get-in-td   (cp.art/external-function-detail env this-sym)
        map-td      (cp.ana.disp/-analyze! env m)
        path-td     (cp.ana.disp/-analyze! env path)
        dflt-td     (when dflt (cp.ana.disp/-analyze! env dflt)) ]
    (let [env (cp.art/update-location env (meta path))]
      (doseq [sample-path (::cp.art/samples path-td)]
        (reduce (fn [samples ?k]
                  (when-not (some #(contains? % ?k) samples)
                    (cp.art/record-warning! env ?k :warning/get-in-might-never-succeed))
                  (map #(get % ?k) samples))
          (::cp.art/samples map-td)
          sample-path)))
    {::cp.art/samples
     (set
       (mapcat (fn [sample-path]
                 (if-let [spec (and (qualified-keyword? (last sample-path))
                                 (cp.spec/lookup env (last sample-path)))]
                   (cp.sampler/try-sampling! env (cp.spec/generator env (last sample-path)))
                   (::cp.art/samples
                     (cp.fnt/analyze-function-call! env get-in-td
                       (cond-> [map-td {::cp.art/samples #{sample-path}}]
                         dflt (conj dflt-td))))))
         (::cp.art/samples path-td))) }))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/get-in [env sexpr] (analyze:get-in env sexpr))
