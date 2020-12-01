(ns com.fulcrologic.copilot.analysis.analyzer.functions
  (:require
    [com.fulcrologic.copilot.analysis.analyzer.dispatch :as cp.ana.disp]
    [com.fulcrologic.copilot.analysis.function-type :as cp.fnt]
    [com.fulcrologic.copilot.analysis.sampler :as cp.sampler]
    [com.fulcrologic.copilot.analysis.spec :as cp.spec]
    [com.fulcrologic.copilot.artifacts :as cp.art]))

(defn can-get-in?! [env samples child-kw]
  ;(let [samples (cp.sampler/try-sampling! env (cp.spec/generator env parent-kw))])
  (not (some #(contains? child-kw %) samples)))

(defn analyze:get-in [env [this-sym m path]]
  (let [get-in-td   (cp.art/external-function-detail env this-sym)
        map-td      (cp.ana.disp/-analyze! env m)
        path-td     (cp.ana.disp/-analyze! env path)
        pure-return (cp.fnt/analyze-function-call! env get-in-td [map-td path-td])]
    (let [env (cp.art/update-location env (meta path))]
      (doseq [sample-path (::cp.art/samples path-td)]
        (reduce (fn [samples ?k]
                  (when-not (some #(contains? % ?k) samples)
                    (cp.art/record-warning! env ?k :warning/get-in-might-never-succeed))
                  (map #(get % ?k) samples))
          (::cp.art/samples map-td)
          sample-path)))
    (if-let [spec (and (qualified-keyword? (last path))
                    (cp.spec/lookup env (last path)))]
      #::cp.art{:spec spec
                 :type (str (last path))
                 :samples (cp.sampler/try-sampling! env (cp.spec/generator env (last path)))}
      pure-return)))

(defmethod cp.ana.disp/analyze-mm 'clojure.core/get-in [env sexpr] (analyze:get-in env sexpr))
