(ns user
  (:require
    [clojure.tools.namespace.repl :as tools-ns :refer [set-refresh-dirs]]
    [com.fulcrologic.guardrails-pro.daemon.http-server]
    [com.fulcrologic.guardrails.core :refer [>defn >def => | ?]]
    [mount.core :as mount]))

(set-refresh-dirs "src/main" "src/dev")

(defn start [] (mount/start))

(defn stop [] (mount/stop))

(defn restart [] (stop) (tools-ns/refresh :after 'user/start))

(comment
  (restart)
  )
