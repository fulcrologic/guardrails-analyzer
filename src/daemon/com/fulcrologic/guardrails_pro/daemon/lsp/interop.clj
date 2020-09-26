(ns com.fulcrologic.guardrails-pro.daemon.lsp.interop)

(defn json->clj [json-element]
  (cond
    (nil? json-element) nil
    (.isJsonNull json-element) nil

    (.isJsonArray json-element)
    (mapv json->clj (iterator-seq (.iterator (.getAsJsonArray json-element))))

    (.isJsonObject json-element)
    (->> json-element
         (.getAsJsonObject)
         (.entrySet)
         (map (juxt key (comp json->clj val)))
         (into {}))

    (.isJsonPrimitive json-element)
    (let [json-primitive (.getAsJsonPrimitive json-element)]
      (cond
        (.isString json-primitive) (.getAsString json-primitive)
        (.isNumber json-primitive) (.getAsLong json-primitive)
        (.isBoolean json-primitive) (.getAsBoolean json-primitive)
        :else json-primitive))

    :else
    json-element))
