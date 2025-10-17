(ns com.fulcrologic.guardrails-analyzer.daemon.lsp.diagnostics
  (:require
    [com.fulcrologic.guardrails-analyzer.artifacts :as cp.art]
    [com.fulcrologicpro.taoensso.timbre :as log]
    [com.rpl.specter :as $])
  (:import
    (java.net URI)
    (org.eclipse.lsp4j
      Diagnostic DiagnosticSeverity
      MessageParams MessageType
      Position PublishDiagnosticsParams Range)))

(defonce client:id->info (atom {}))

(defonce client-id->open-uri (atom {}))

(defn problem->diagnostic
  [{::cp.art/keys [problem-type message
                   line-start line-end
                   column-start column-end]}]
  (new Diagnostic
    (new Range
      (new Position (dec line-start) (dec column-start))
      (new Position (dec line-end) (dec column-end)))
    message
    (case (namespace problem-type)
      "error" DiagnosticSeverity/Error
      "warning" DiagnosticSeverity/Warning
      "info" DiagnosticSeverity/Information
      "hint" DiagnosticSeverity/Hint
      DiagnosticSeverity/Error)
    "guardrails.analyzer"))

(defn publish-problems-for [remote uri problems]
  (.publishDiagnostics remote
    (new PublishDiagnosticsParams uri
      (mapv problem->diagnostic problems))))

(defn client-for-project? [project-dir [_ client-info]]
  (= project-dir (:project-dir client-info)))

(defn update-problems! [{:keys [project-dir]} problems]
  (doseq [[client-id {:keys [remote]}]
          (filter (partial client-for-project? project-dir) @client:id->info)]
    (when-let [uri (get @client-id->open-uri client-id)]
      (let [file (.getPath (new URI uri))]
        (publish-problems-for remote uri
          ($/select
            [($/walker ::cp.art/problem-type)
             ($/pred (comp (partial = file) ::cp.art/file))]
            problems))))))

(defn report-error! [{:keys [project-dir]} error]
  (doseq [[_ {:keys [remote]}]
          (filter (partial client-for-project? project-dir) @client:id->info)]
    (.showMessage remote
      (new MessageParams
        MessageType/Error
        error))))

(defn report-no-checker! [client-id path]
  (let [fmt "Failed to find any checkers for that project! Make sure one is running for `%s`."
        msg (format fmt path)]
    (log/error msg)
    (report-error! (get @client:id->info client-id) msg)))
