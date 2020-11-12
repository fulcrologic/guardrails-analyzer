(ns com.fulcrologic.guardrails-pro.analysis.analyzer.functions
  (:require
    [com.fulcrologic.guardrails-pro.analysis.analyzer.dispatch :as grp.ana.disp]
    [com.fulcrologic.guardrails-pro.analysis.function-type :as grp.fnt]
    [com.fulcrologic.guardrails-pro.analysis.sampler :as grp.sampler]
    [com.fulcrologic.guardrails-pro.analysis.spec :as grp.spec]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]))

(defn can-get-in?! [env samples child-kw]
  ;(let [samples (grp.sampler/try-sampling! env (grp.spec/generator env parent-kw))])
  (not (some #(contains? child-kw %) samples)))

(defn analyze:get-in [env [this-sym m path]]
  (let [get-in-td   (grp.art/external-function-detail env this-sym)
        map-td      (grp.ana.disp/-analyze! env m)
        path-td     (grp.ana.disp/-analyze! env path)
        pure-return (grp.fnt/analyze-function-call! env get-in-td [map-td path-td])]
    (let [env (grp.art/update-location env (meta path))]
      (doseq [sample-path (::grp.art/samples path-td)]
        (reduce (fn [samples ?k]
                  (when-not (some #(contains? % ?k) samples)
                    (grp.art/record-warning! env ?k :warning/get-in-might-never-succeed))
                  (map #(get % ?k) samples))
          (::grp.art/samples map-td)
          sample-path)))
    (if-let [spec (and (qualified-keyword? (last path))
                    (grp.spec/lookup env (last path)))]
      #::grp.art{:spec spec
                 :type (str (last path))
                 :samples (grp.sampler/try-sampling! env (grp.spec/generator env (last path)))}
      pure-return)))

(defmethod grp.ana.disp/analyze-mm 'get-in [env sexpr] (analyze:get-in env sexpr))
(defmethod grp.ana.disp/analyze-mm 'clojure.core/get-in [env sexpr] (analyze:get-in env sexpr))
