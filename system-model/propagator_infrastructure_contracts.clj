(ns system-model.propagator-infrastructure-contracts
  "Review examples composing existing public APIs; not a new production API."
  (:require [clojure.spec.alpha :as s]
            [propagators.compiler-2.main :as compiler]
            [propagators.compiler-2.cps-core :as cps-core]
            [propagators.compiler-2.compiler.basis :as basis]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.compiler-2.runtime.boundary :as boundary]
            [propagators.compiler-2.runtime.session.extension :as extension]
            [propagators.compiler-common.cps :as cps]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.support-collection :as collection]
            [propagators.gur :as gur]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]))

;; Proposed documentation specs over existing representations.
(s/def ::network net/network?)
(s/def ::node-id ids/node-id?)
(s/def ::message message/message?)

;; Existing composition: the cell evaluator emits tasks; only the runner drains them.
(defn publish-and-settle [network messages]
  (let [[tasks updated] (core/eval-cells messages network)]
    (runner/completed-network (runner/run-network tasks updated))))

(defn copy-example [input-value]
  (let [input (ids/new-node-id)
        output (ids/new-node-id)
        initial (nb/install-cells [input output])
        copy (prop/primitive-propagator :model-review/copy identity)
        [_ installed] ((copy input output) initial)
        settled (publish-and-settle installed [(message/message input input-value)])]
    (net/network-cell-strongest settled output)))

;; Existing composition: declaration data precedes interpretation.
;; GUR contributes stable identity; generic patches install the topology.
(defn declaration-example []
  (let [id (gur/stable-node-id [:model-review :declared-cell])
        declaration (gur/declare-cell id)
        [_ installed] (core/eval-effects [declaration] net/empty-net)
        [_ repeated] (core/eval-effects [declaration] installed)]
    (= installed repeated)))

;; These are external accepted input forms, normalized through public constructors.
;; TTMS owns support policy; a compound slot stores the resulting content as data.
(defn supported-slot-example []
  (let [source (ids/new-node-id)
        evidence (collection/content
                  {:base 10
                   :support #{{:source source
                               :timestamp 1
                               :premises-status :active}}})
        owner (ids/new-node-id)
        output (ids/new-node-id)
        initial (nb/install-cells [owner output])
        [accessor installed] ((obj/p:slot :reading output owner) initial)
        ready (runner/completed-network (runner/run-network [accessor] installed))
        settled (publish-and-settle
                 ready [(message/message owner
                                         (obj/compound-object {:reading evidence}))])]
    (= evidence (net/network-cell-content settled output))))

(defn composed-activation-example []
  (let [output (ids/new-node-id)
        original (prop/prop :model-review/original
                            (fn [_ _ _] [(message/message output 41)]))
        composed (prop/prop
                  :model-review/composed
                  (prop/compose-activation (prop/prop-f original)
                                           (message/lift-message inc)))
        run (fn [declaration]
              (message/message-value
               (first ((prop/prop-f declaration) [] [output] net/empty-net))))]
    (and (= 41 (run original)) (= 42 (run composed)))))

(defn compiler-example []
  (let [compiled (compiler/compile-source "(+ 20 22)")
        settled (runner/completed-network
                 (runner/run-network (:props compiled) (:net compiled)))]
    (net/network-cell-strongest settled (:cell compiled))))

(defn compiler-combinator-example []
  (let [custom (cps/make-compiler
                (cps/compose-rules
                 (cps/on #(= :model-review/constant (ast/type %))
                         (fn [compile-k state _expression continuation]
                           (cps/call compile-k state (ast/lit 42) continuation)))
                 cps-core/compiler-dispatch))
        expression (obj/compound-object {ast/type-slot :model-review/constant})
        compiled (cps-core/compile-expr-with-bindings
                  expression (basis/default-bindings) {:compiler custom})
        settled (runner/completed-network
                 (runner/run-network (:props compiled) (:net compiled)))]
    (net/network-cell-strongest settled (:cell compiled))))

;; Declare primitive and effect capabilities together; do not install or execute IO.
(defn session-extension-example []
  (extension/extension-bundle
   {:id :model-review/session
    :bindings [['review-id
                (operator/propagator-operator
                 {:name :model-review/id
                  :activate (fn [network inputs outputs]
                              [(message/message
                                (first outputs)
                                (net/network-cell-strongest network (first inputs)))])})]]
    :effects (extension/extension-effects extension/core-extension)}))

;; Construction declares work; no host interpreter or file IO is invoked here.
(defn boundary-example []
  (boundary/environment-effect-request
   :model-review/save :environment/save
   {:description "data-only review request"} (ids/new-node-id)))

(defn check-contracts []
  (let [checks {:copy (= 42 (copy-example 42))
                :declaration-idempotence (declaration-example)
                :slot-preserves-content (supported-slot-example)
                :activation-composition (composed-activation-example)
                :compiler-result (= 42 (compiler-example))
                :compiler-combinators (= 42 (compiler-combinator-example))
                :session-extension
                (= :model-review/session
                   (extension/extension-id (session-extension-example)))
                :boundary-declaration
                (= :environment (:boundary/port (boundary-example)))}]
    (when-not (every? true? (vals checks))
      (throw (ex-info "Model review composition failed" {:checks checks})))
    checks))
