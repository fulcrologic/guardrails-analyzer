(ns com.fulcrologic.guardrails-analyzer.daemon.server.checkers
  (:require
   [com.fulcrologic.guardrails-analyzer.daemon.server.connection-management :as cp.conn]
   [com.fulcrologic.guardrails-analyzer.forms :as cp.forms]
   [com.fulcrologic.guardrails-analyzer.reader :as cp.reader]
   [com.fulcrologicpro.fulcro.networking.websocket-protocols :as wsp]
   [com.fulcrologicpro.taoensso.timbre :as log])
  (:import
   (java.nio.file Paths)))

(defn- ->normalized-path
  "Returns `path` resolved to an absolute, traversal-free `java.nio.file.Path`,
   or `nil` if it cannot be parsed. Uses `toAbsolutePath` rather than `toRealPath`
   so paths that do not exist on disk yet (the editor may be checking an
   unsaved buffer) still normalize cleanly; `..` segments are still removed by
   `normalize`."
  [^String path]
  (try
    (-> (Paths/get path (make-array String 0))
        (.toAbsolutePath)
        (.normalize))
    (catch Throwable _ nil)))

(defn safe-path-under?
  "Returns `true` iff the editor-supplied `path`, after canonicalization, is
   contained within `project-dir`. Rejects `..`-escaping paths and absolute
   paths outside the project. Comparison is path-component-wise (via
   `Path/startsWith`), so `/projA` does not falsely match `/projAB/...`."
  [path project-dir]
  (boolean
   (when (and (string? path) (string? project-dir))
     (when-let [target (->normalized-path path)]
       (when-let [root (->normalized-path project-dir)]
         (.startsWith target root))))))

(defn notify-checker! [ws checker-cid event checker-info->data]
  (log/debug "notifiying checkers of event:" event)
  (when-let [checker-info (get @cp.conn/registered-checkers checker-cid)]
    (log/debug "notifying checker:" checker-cid checker-info)
    (wsp/push ws checker-cid event
              (checker-info->data checker-info)))
  {})

(defn opts->check-type [{:keys [refresh?]}]
  (if refresh? :refresh-and-check! :check!))

(defn- path-allowed-for-checker?
  "Returns `true` if the checker registered under `checker-cid` exists AND
   `path` canonicalizes to a location inside its `:project-dir`. Logs a warning
   and returns `false` for any rejected path-traversal attempt."
  [checker-cid path]
  (if-let [checker-info (get @cp.conn/registered-checkers checker-cid)]
    (or (safe-path-under? path (:project-dir checker-info))
        (do (log/warn "Rejecting path outside project-dir:"
                      {:path path
                       :checker-cid checker-cid
                       :project-dir (:project-dir checker-info)})
            false))
    false))

(defn check-file! [ws checker-cid path opts]
  (when (path-allowed-for-checker? checker-cid path)
    (let [check-type (opts->check-type opts)]
      (notify-checker! ws checker-cid check-type
                       (fn [{:keys [checker-type]}]
                         (-> (cp.reader/read-file path checker-type)
                             (update :forms cp.forms/form-expression)
                             (assoc :check-command-type [check-type :file])))))))

(defn root-form-at? [cursor-line ?form]
  (let [{:keys [line end-line]} (meta ?form)]
    (<= line cursor-line end-line)))

(defn check-root-form! [ws checker-cid path line opts]
  (when (path-allowed-for-checker? checker-cid path)
    (let [check-type (opts->check-type opts)]
      (notify-checker! ws checker-cid check-type
                       (fn [{:keys [checker-type]}]
                         (-> (cp.reader/read-file path checker-type)
                             (update :forms
                                     #(->> %
                                           (filter (partial root-form-at? line))
                                           (cp.forms/form-expression)))
                             (assoc :check-command-type [check-type :root-form])))))))
