(ns propagators.prototype-2.core
  "Prototype-2: edge-chain networked system.

  Design sketch — a networked system that generalizes propagators.

  Every node holds EdgeChains for its inputs and outputs. The chain is the
  unit of topology and of dependence.

  A propagator is a specialized node. `pending-propagator-constructor` puts
  `propagator-f` (and intended ports) in value with a blank topology node.
  Evaluation builds EdgeChains when none of the inputs are empty.

  Invariant: a *primitive* propagator builds its topology at most once.
  After that, the node is simply wired; we do not track pending-vs-alive as
  two kinds of propagator. Only *compound* propagators may be partially
  built (more topology added later as further inputs arrive).

  Smart skip (primitive and compound): cells are only nothing or a concrete
  value. If every input and every output is already satisfied, do not build —
  there is nothing to compute or expand. Compounds especially benefit: their
  inner pending network is never installed when the boundary is already done.

  Dependence follows realized chains: each new node inherits upstream origin
  sets and sets tail to its downstream cell. A chain is *complete* when its
  terminal is satisfied and every origin is satisfied. Completed chains may
  optionally be GC'd.

  Modules: edge-chain, types, network, construct, evaluate, primitives,
  session, church, p-list, trace-ops, debug.

  L-2 (compiler) exposes `origins` / `neighbors` over EdgeChain dependence."
  (:require [propagators.prototype-2.church :as church]
            [propagators.prototype-2.construct :as ctor]
            [propagators.prototype-2.debug :as debug]
            [propagators.prototype-2.edge-chain :as ec]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.p-list :as plist]
            [propagators.prototype-2.primitives :as prim]
            [propagators.prototype-2.session :as session]
            [propagators.prototype-2.trace-ops :as trace]
            [propagators.prototype-2.types :as t]))

;; edge-chain
(def edge-chain ec/edge-chain)
(def empty-edge-chain ec/empty-edge-chain)
(def edge-chain? ec/edge-chain?)
(def edge-chain-empty? ec/edge-chain-empty?)
(def edge-chain-origins ec/edge-chain-origins)
(def edge-chain-heads ec/edge-chain-heads)
(def edge-chain-tail ec/edge-chain-tail)

;; types
(def node t/node)
(def blank-node t/blank-node)
(def node? t/node?)
(def node-inputs t/node-inputs)
(def node-outputs t/node-outputs)
(def topology-built? t/topology-built?)
(def propagator-f? t/propagator-f?)
(def propagator-f t/propagator-f)
(def compound-f? t/compound-f?)
(def compound-f t/compound-f)
(def propagator-value? t/propagator-value?)
(def propagator-output t/propagator-output)
(def nothing t/nothing)
(def nothing? t/nothing?)
(def cell t/cell)
(def cell? t/cell?)
(def cell-content t/cell-content)
(def concrete-cell? t/concrete-cell?)

;; network
(def network net/network)
(def empty-network net/empty-network)
(def network? net/network?)
(def network-topology net/network-topology)
(def network-value net/network-value)
(def network-name net/network-name)
(def network-queue net/network-queue)
(def empty-queue net/empty-queue)
(def network-with-topology net/network-with-topology)
(def network-with-value net/network-with-value)
(def network-with-name net/network-with-name)
(def network-with-queue net/network-with-queue)
(def enqueue net/enqueue)
(def enqueue-all net/enqueue-all)
(def assoc-node net/assoc-node)
(def assoc-value net/assoc-value)
(def bind-name net/bind-name)
(def lookup-name net/lookup-name)
(def lookup-node net/lookup-node)
(def lookup-value net/lookup-value)
(def cell-value net/cell-value)
(def inputs-ready? net/inputs-ready?)
(def outputs-satisfied? net/outputs-satisfied?)
(def boundary-satisfied? net/boundary-satisfied?)
(def ensure-cell net/ensure-cell)
(def ensure-node net/ensure-node)
(def ensure-cells net/ensure-cells)
(def ensure-nodes net/ensure-nodes)

;; construct
(def pending-propagator-constructor ctor/pending-propagator-constructor)
(def pending-compound-constructor ctor/pending-compound-constructor)

;; evaluate
(def origins-of ev/origins-of)
(def compose-edge-chain ev/compose-edge-chain)
(def publish-dependence ev/publish-dependence)
(def build-propagator-topology ev/build-propagator-topology)
(def expand-compound ev/expand-compound)
(def evaluate-propagator ev/evaluate-propagator)
(def write-cell ev/write-cell)
(def empty-stream ev/empty-stream)
(def run-tasks ev/run-tasks)

;; primitives
(def id-f prim/id-f)
(def install-id prim/install-id)
(def install-id-chain prim/install-id-chain)
(def install-bi-sync prim/install-bi-sync)
(def install-bi-sync-chain prim/install-bi-sync-chain)
(def install-compound-id-bridge prim/install-compound-id-bridge)

;; session
(def satisfied? session/satisfied?)
(def dependence-of session/dependence-of)
(def first-dependences session/first-dependences)
(def tell session/tell)
(def chain-complete? session/chain-complete?)

;; church
(def visitor church/visitor)
(def visitor? church/visitor?)
(def install-cons church/install-cons)
(def apply-visitor church/apply-visitor)
(def install-car church/install-car)
(def install-cdr church/install-cdr)
(def cons-then church/cons-then)

;; p-list (Church cons spine — no separate Pair)
(def empty-list plist/empty-list)
(def empty-list? plist/empty-list?)
(def church-cons plist/church-cons)
(def church-cons? plist/church-cons?)
(def p-list-value? plist/p-list-value?)
(def p-list? plist/p-list?)
(def p-list-completed? plist/p-list-completed?)
(def p-list-partial? plist/p-list-partial?)

;; trace-ops (L-2 origins / neighbors)
(def resolve-trace-target trace/resolve-trace-target)
(def upstream-neighbors trace/upstream-neighbors)
(def materialize-id-list trace/materialize-id-list)
(def install-origins-prop trace/install-origins-prop)
(def install-neighbors-prop trace/install-neighbors-prop)

;; debug
(def describe-propagator debug/describe-propagator)
(def evaluate-propagator-debug debug/evaluate-propagator-debug)
(def run-tasks-debug debug/run-tasks-debug)
(def tell-debug debug/tell-debug)
