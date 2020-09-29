(ns com.fulcrologic.guardrails-pro.artifacts-spec
  (:require
    [com.fulcrologic.guardrails.registry :as gr.reg]
    [com.fulcrologic.guardrails-pro.artifacts :as grp.art]
    [fulcro-spec.check :as _]
    [fulcro-spec.core :refer [specification component assertions]]))

(specification "fix-kw-nss"
  (assertions
    (#'grp.art/fix-kw-nss {::gr.reg/foo {::gr.reg/bar 1}
                           ::_/a ::_/b
                           :qux {:wub 2}})
    => {::grp.art/foo {::grp.art/bar 1}
        ::_/a ::_/b
        :qux {:wub 2}}))

(specification "resolve-quoted-specs"
  (let [spec-registry {'int? int?
                       'string? string?}]
    (assertions
      (#'grp.art/resolve-quoted-specs spec-registry
        {::grp.art/quoted.argument-specs '[int?]})
      => {::grp.art/quoted.argument-specs '[int?]
          ::grp.art/argument-specs [int?]}
      (#'grp.art/resolve-quoted-specs spec-registry
        {::grp.art/quoted.argument-specs '[int?]
         ::grp.art/quoted.return-spec 'string?})
      => {::grp.art/quoted.argument-specs '[int?]
          ::grp.art/argument-specs [int?]
          ::grp.art/quoted.return-spec 'string?
          ::grp.art/return-spec string?})))
