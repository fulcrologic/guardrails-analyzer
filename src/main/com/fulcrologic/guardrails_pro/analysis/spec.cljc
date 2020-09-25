(ns com.fulcrologic.guardrails-pro.analysis.spec
  (:refer-clojure :exclude [-lookup])
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [taoensso.timbre :as log]))

;; NOTE: what about custom generator options? (eg: size & seed)
(defprotocol ISpec
  (-lookup [this value])
  (-valid? [this spec value])
  (-explain [this spec value])
  (-generator [this spec])
  (-generate [this spec])
  (-sample [this spec]))

(defrecord ClojureSpecAlpha [] ISpec
  (-lookup [this value] (s/get-spec value))
  (-valid? [this spec value] (s/valid? spec value))
  (-explain [this spec value] (s/explain-str spec value))
  (-generator [this spec] (s/gen spec))
  (-generate [this spec] (gen/generate spec))
  (-sample [this spec] (gen/sample spec)))

(comment
  (defrecord Malli ISpec
    (-lookup [this value] (malli/schema value))
    (-valid? [this spec value] (boolean (malli/validate spec value)))
    (-explain [this spec value] (malli/explain spec value))
    (-generator [this spec] (malli/generator spec))
    (-generate [this spec] (malli/generate spec))
    (-sample [this spec] (malli/sample spec)))
  )

;; TODO: look in guardrails config for
;; - a dispatch keyword or ns sym
;; - use it to pick implementation
;; TODO: overriden by namespace metadata
;; ? controlled by a dynamic binding ?

(defn with-spec-impl [env impl-type]
  (assoc env ::impl
    (case impl-type
      :clojure.spec.alpha (->ClojureSpecAlpha)
      (->ClojureSpecAlpha))))

(defn lookup [env value] (-lookup (::impl env) value))
(defn valid? [env spec value] (-valid? (::impl env) spec value))
(defn explain [env spec value] (-explain (::impl env) spec value))
(defn generator [env spec]
  (try (-generator (::impl env) spec)
    (catch #? (:clj Exception :cljs :default) e
      (log/error e "spec/generator failed to gen for" spec)
      nil)))
(defn generate [env spec] (-generate (::impl env) spec))
(defn sample [env spec] (-sample (::impl env) spec))
