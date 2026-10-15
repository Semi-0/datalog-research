(ns examples.lain.rule-fixedpoint.catalog
  "Opt-in declaration constructors. Concrete rule heads/bodies belong in Lain."
  (:require [examples.lain.amb.checkpoint-search :as checkpoint]
            [examples.lain.rule-fixedpoint.channels :as channels]
            [examples.lain.rule-fixedpoint.model :as model]
            [examples.lain.rule-fixedpoint.query :as query]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-2.runtime.session.state :as session]
            [propagators.experimental.ttms-primitives :as ttms]))

(def session-extension
  (extension/extension-bundle
   {:id ::rules :effects []
    :bindings [['relation (query/primitive ::relation model/relation)]
               ['rule (query/primitive ::rule model/rule)]
               ['guarded-rule (query/primitive ::guarded model/guarded-rule)]
               ['copy-rule (query/primitive ::copy model/copy-rule)]
               ['step-rule (query/primitive ::step model/step-rule)]
               ['rule-set (query/primitive ::catalog vector)]]}))

(defn initialize []
  (-> (session/empty-state)
      (extension/install-session-extension ttms/session-extension {})
      (extension/install-session-extension session-extension {})))

(defn compile-source [source]
  (let [initial (initialize)
        compiled (compiler/compile-source source (:program/env initial)
                                          {:net (:program/net initial) :environment-props (:program/props initial)})
        settled (checkpoint/settle {:network (:net compiled) :tasks (:props compiled)})
        rules (channels/base (:network settled) (:cell compiled))]
    (if (and (vector? rules) (seq rules))
      {:rules (model/checked-catalog rules) :network (:network settled) :source source}
      (throw (ex-info "Lain catalog did not produce rule declarations" {:value rules})))))
