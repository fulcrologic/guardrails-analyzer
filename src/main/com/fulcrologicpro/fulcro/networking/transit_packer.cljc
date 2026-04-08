(ns com.fulcrologicpro.fulcro.networking.transit-packer
  (:require
    [com.fulcrologicpro.fulcro.algorithms.transit :as ot]
    [com.fulcrologicpro.taoensso.timbre :as log]
    [com.fulcrologicpro.taoensso.sente.packers.transit :as st])
  #?(:clj
     (:import [com.fulcrologicpro.com.cognitect.transit ReadHandler]
              [com.fulcrologicpro.fulcro.algorithms.tempid TempId])))

(defn make-packer
  "Returns a json packer for use with sente."
  [{:keys [read write]}]
  (log/info "Building websocket packer with support for the following custom types: " (keys (ot/read-handlers)))
  (st/->TransitPacker :json
    {:handlers (cond-> (ot/write-handlers)
                 write (merge write))}
    {:handlers (cond-> (ot/read-handlers)
                 read (merge read))}))
