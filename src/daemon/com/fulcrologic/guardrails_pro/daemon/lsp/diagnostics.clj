(ns com.fulcrologic.guardrails-pro.daemon.lsp.diagnostics
  (:require
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [com.fulcrologicpro.com.rpl.specter :as $]
    [com.fulcrologic.guardrails-pro.logging :as log])
  (:import
    (org.eclipse.lsp4j Diagnostic DiagnosticSeverity Position PublishDiagnosticsParams Range)
    (java.net URI)))

(defonce clients (atom {}))

(defonce currently-open-uri (atom nil))

(defn problem->diagnostic
  [{::grp.art/keys [problem-type message
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
    "guardrails-pro"))

(defn publish-problems-for [uri problems]
  (doseq [[_ client] @clients]
    (.publishDiagnostics client
      (new PublishDiagnosticsParams uri
        (mapv problem->diagnostic problems)))))

(defn update-problems! [problems]
  (when-let [uri @currently-open-uri]
    (let [file (.getPath (new URI uri))]
      (publish-problems-for uri
        (log/spy :debug :update-problems!
          ($/select
            [($/walker ::grp.art/problem-type)
             ($/pred (comp (partial = file) ::grp.art/file))]
            problems))))))
