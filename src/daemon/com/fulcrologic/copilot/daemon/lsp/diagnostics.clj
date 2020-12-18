(ns com.fulcrologic.copilot.daemon.lsp.diagnostics
  (:require
    [com.fulcrologic.copilot.artifacts :as cp.art]
    [com.fulcrologicpro.com.rpl.specter :as $])
  (:import
    (org.eclipse.lsp4j
      Diagnostic DiagnosticSeverity
      MessageParams MessageType
      Position PublishDiagnosticsParams Range)
    (java.net URI)))

(defonce clients (atom {}))

(defonce client-id->open-uri (atom {}))

(defn problem->diagnostic
  [{::cp.art/keys [problem-type message
                   line-start line-end
                   column-start column-end]}]
  (new Diagnostic
    (new Range
      (new Position (dec line-start) (dec column-start))
      (new Position (dec line-end)   (dec column-end)))
    message
    (case (namespace problem-type)
      "error"   DiagnosticSeverity/Error
      "warning" DiagnosticSeverity/Warning
      "info"    DiagnosticSeverity/Information
      "hint"    DiagnosticSeverity/Hint
      DiagnosticSeverity/Error)
    "guardrails.copilot"))

(defn publish-problems-for [remote uri problems]
  (.publishDiagnostics remote
    (new PublishDiagnosticsParams uri
      (mapv problem->diagnostic problems))))

(defn client-for-project? [project-dir [_ client-info]]
  (= project-dir (:project-dir client-info)))

(defn update-problems! [{:keys [project-dir]} problems]
  (doseq [[client-id {:keys [remote]}]
          (filter (partial client-for-project? project-dir) @clients)]
    (when-let [uri (get @client-id->open-uri client-id)]
      (let [file (.getPath (new URI uri))]
        (publish-problems-for remote uri
          ($/select
            [($/walker ::cp.art/problem-type)
             ($/pred (comp (partial = file) ::cp.art/file))]
            problems))))))

(defn report-error! [{:keys [project-dir]} error]
  (doseq [[_ {:keys [remote]}] (filter (partial client-for-project? project-dir) @clients)]
    (.showMessage remote
      (new MessageParams
        MessageType/Error
        error))))
