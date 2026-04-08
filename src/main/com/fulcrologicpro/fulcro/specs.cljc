(ns com.fulcrologicpro.fulcro.specs
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>def]]
    [com.fulcrologicpro.fulcro.algorithms.do-not-use :as futil]
    [com.fulcrologicpro.edn-query-language.core :as eql]))

;; ================================================================================
;; Transaction Specs
;; ================================================================================

(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/id uuid?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/idx int?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/created inst?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/started inst?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/finished inst?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/tx vector?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/abort-id any?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/options (s/keys
                                                                 :opt [:com.fulcrologicpro.fulcro.algorithms.tx-processing/abort-id]
                                                                 :opt-un [:com.fulcrologicpro.fulcro.algorithms.tx-processing/abort-id]))
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/started? set?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/complete? set?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/results (s/map-of keyword? any?))
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/progress (s/map-of keyword? any?))
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/transmitted-ast-nodes (s/map-of keyword? :com.fulcrologicpro.edn-query-language.ast/node))
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/dispatch map?) ; a tree is also a node
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/ast :com.fulcrologicpro.edn-query-language.ast/node)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/original-ast-node :com.fulcrologicpro.fulcro.algorithms.tx-processing/ast)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/desired-ast-nodes (s/map-of keyword? :com.fulcrologicpro.edn-query-language.ast/node))
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/tx-element (s/keys
                                                                    :req [:com.fulcrologicpro.fulcro.algorithms.tx-processing/idx
                                                                          :com.fulcrologicpro.fulcro.algorithms.tx-processing/original-ast-node
                                                                          :com.fulcrologicpro.fulcro.algorithms.tx-processing/started?
                                                                          :com.fulcrologicpro.fulcro.algorithms.tx-processing/complete?
                                                                          :com.fulcrologicpro.fulcro.algorithms.tx-processing/results
                                                                          :com.fulcrologicpro.fulcro.algorithms.tx-processing/dispatch]
                                                                    :opt [:com.fulcrologicpro.fulcro.algorithms.tx-processing/desired-ast-nodes
                                                                          :com.fulcrologicpro.fulcro.algorithms.tx-processing/transmitted-ast-nodes
                                                                          :com.fulcrologicpro.fulcro.algorithms.tx-processing/progress]))
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/elements (s/coll-of :com.fulcrologicpro.fulcro.algorithms.tx-processing/tx-element :kind vector?))
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/tx-node
  (s/keys :req [:com.fulcrologicpro.fulcro.algorithms.tx-processing/id
                :com.fulcrologicpro.fulcro.algorithms.tx-processing/created
                :com.fulcrologicpro.fulcro.algorithms.tx-processing/options
                :com.fulcrologicpro.fulcro.algorithms.tx-processing/tx
                :com.fulcrologicpro.fulcro.algorithms.tx-processing/elements]
    :opt [:com.fulcrologicpro.fulcro.algorithms.tx-processing/started
          :com.fulcrologicpro.fulcro.algorithms.tx-processing/finished]))

(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/result-handler fn?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/update-handler fn?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/active? boolean?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/parallel? boolean?)

(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/send-node
  (s/keys
    :req [:com.fulcrologicpro.fulcro.algorithms.tx-processing/id
          :com.fulcrologicpro.fulcro.algorithms.tx-processing/idx
          :com.fulcrologicpro.fulcro.algorithms.tx-processing/ast
          :com.fulcrologicpro.fulcro.algorithms.tx-processing/result-handler
          :com.fulcrologicpro.fulcro.algorithms.tx-processing/update-handler
          :com.fulcrologicpro.fulcro.algorithms.tx-processing/active?]
    :opt [:com.fulcrologicpro.fulcro.algorithms.tx-processing/options]))

(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/submission-queue (s/coll-of :com.fulcrologicpro.fulcro.algorithms.tx-processing/tx-node :kind vector?))
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/active-queue (s/coll-of :com.fulcrologicpro.fulcro.algorithms.tx-processing/tx-node :kind vector?))
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/send-queue (s/coll-of :com.fulcrologicpro.fulcro.algorithms.tx-processing/send-node :kind vector?))
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/send-queues (s/map-of :com.fulcrologicpro.fulcro.application/remote-name :com.fulcrologicpro.fulcro.algorithms.tx-processing/send-queue))

(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/activation-scheduled? boolean?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/sends-scheduled? boolean?)
(>def :com.fulcrologicpro.fulcro.algorithms.tx-processing/queue-processing-scheduled? boolean?)

;; ================================================================================
;; Application Specs
;; ================================================================================
(>def :com.fulcrologicpro.fulcro.application/state-atom futil/atom?)
(>def :com.fulcrologicpro.fulcro.application/app-root (s/nilable any?))
(>def :com.fulcrologicpro.fulcro.application/runtime-atom futil/atom?)

;; indexing-related
(>def :com.fulcrologicpro.fulcro.application/ident->components (s/map-of eql/ident? set?))
(>def :com.fulcrologicpro.fulcro.application/prop->classes (s/map-of (s/or :keyword keyword? :ident eql/ident?) set?))
(>def :com.fulcrologicpro.fulcro.application/class->components (s/map-of keyword? set?))
(>def :com.fulcrologicpro.fulcro.application/idents-in-joins (s/coll-of eql/ident? :kind set?))
(>def :com.fulcrologicpro.fulcro.application/indexes (s/keys :opt-un [:com.fulcrologicpro.fulcro.application/ident->components
                                                                   :com.fulcrologicpro.fulcro.application/keyword->components
                                                                   :com.fulcrologicpro.fulcro.application/idents-in-joins
                                                                   :com.fulcrologicpro.fulcro.application/class->components]))

(>def :com.fulcrologicpro.fulcro.application/remote-name keyword?)
(>def :com.fulcrologicpro.fulcro.application/remote-names (s/coll-of keyword? :kind set?))
(>def :com.fulcrologicpro.fulcro.application/remotes (s/map-of :com.fulcrologicpro.fulcro.application/remote-name map?))
(>def :com.fulcrologicpro.fulcro.application/active-remotes (s/coll-of :com.fulcrologicpro.fulcro.application/remote-name :kind set?))
(>def :com.fulcrologicpro.fulcro.application/basis-t pos-int?)
(>def :com.fulcrologicpro.fulcro.application/last-rendered-state map?)
(>def :com.fulcrologicpro.fulcro.application/runtime-state (s/keys :req [:com.fulcrologicpro.fulcro.application/app-root
                                                                      :com.fulcrologicpro.fulcro.application/indexes
                                                                      :com.fulcrologicpro.fulcro.application/remotes
                                                                      :com.fulcrologicpro.fulcro.application/basis-t
                                                                      :com.fulcrologicpro.fulcro.application/last-rendered-state
                                                                      :com.fulcrologicpro.fulcro.algorithms.tx-processing/activation-scheduled?
                                                                      :com.fulcrologicpro.fulcro.algorithms.tx-processing/queue-processing-scheduled?
                                                                      :com.fulcrologicpro.fulcro.algorithms.tx-processing/sends-scheduled?
                                                                      :com.fulcrologicpro.fulcro.algorithms.tx-processing/submission-queue
                                                                      :com.fulcrologicpro.fulcro.algorithms.tx-processing/active-queue
                                                                      :com.fulcrologicpro.fulcro.algorithms.tx-processing/send-queues]))
(>def :com.fulcrologicpro.fulcro.algorithm/tx! fn?)
(>def :com.fulcrologicpro.fulcro.algorithm/optimized-render! fn?)
(>def :com.fulcrologicpro.fulcro.algorithm/render! fn?)
(>def :com.fulcrologicpro.fulcro.algorithm/merge* fn?)
(>def :com.fulcrologicpro.fulcro.algorithm/remote-error? fn?)
(>def :com.fulcrologicpro.fulcro.algorithm/schedule-render! fn?)
(>def :com.fulcrologicpro.fulcro.algorithm/global-eql-transform (s/nilable fn?))
(>def :com.fulcrologicpro.fulcro.algorithm/shared-fn (s/nilable fn?))
(>def :com.fulcrologicpro.fulcro.algorithm/global-error-action (s/nilable fn?))
(>def :com.fulcrologicpro.fulcro.algorithm/default-result-action! fn?)
(>def :com.fulcrologicpro.fulcro.algorithm/index-root! fn?)
(>def :com.fulcrologicpro.fulcro.algorithm/index-component! fn?)
(>def :com.fulcrologicpro.fulcro.algorithm/drop-component! fn?)
(>def :com.fulcrologicpro.fulcro.algorithm/props-middleware (s/nilable fn?))
(>def :com.fulcrologicpro.fulcro.algorithm/render-middleware (s/nilable fn?))

(>def :com.fulcrologicpro.fulcro.application/algorithms
  (s/keys
    :req [:com.fulcrologicpro.fulcro.algorithm/default-result-action!
          :com.fulcrologicpro.fulcro.algorithm/drop-component!
          :com.fulcrologicpro.fulcro.algorithm/index-component!
          :com.fulcrologicpro.fulcro.algorithm/index-root!
          :com.fulcrologicpro.fulcro.algorithm/merge*
          :com.fulcrologicpro.fulcro.algorithm/optimized-render!
          :com.fulcrologicpro.fulcro.algorithm/remote-error?
          :com.fulcrologicpro.fulcro.algorithm/render!
          :com.fulcrologicpro.fulcro.algorithm/schedule-render!
          :com.fulcrologicpro.fulcro.algorithm/tx!]
    :opt [:com.fulcrologicpro.fulcro.algorithm/global-eql-transform
          :com.fulcrologicpro.fulcro.algorithm/global-error-action
          :com.fulcrologicpro.fulcro.algorithm/props-middleware
          :com.fulcrologicpro.fulcro.algorithm/render-middleware
          :com.fulcrologicpro.fulcro.algorithm/shared-fn]))

(>def :com.fulcrologicpro.fulcro.application/app (s/keys :req
                                                [:com.fulcrologicpro.fulcro.application/state-atom
                                                 :com.fulcrologicpro.fulcro.application/algorithms
                                                 :com.fulcrologicpro.fulcro.application/runtime-atom]))

