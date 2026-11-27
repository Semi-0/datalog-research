(ns propagators.prototype-2.types
  "Node, Cell, PropagatorF — one pending-propagator type (value + optional expand)."
  (:require [propagators.prototype-2.edge-chain :as ec]))

;; ---------------------------------------------------------------------------
;; Node — EdgeChains only (wiring). Behavior lives in network value.
;; ---------------------------------------------------------------------------

(defrecord Node [inputs outputs])

(defn node
  ([inputs outputs]
   (->Node
    (if (ec/edge-chain? inputs) inputs (ec/empty-edge-chain))
    (if (ec/edge-chain? outputs) outputs (ec/empty-edge-chain))))
  ([]
   (node (ec/empty-edge-chain) (ec/empty-edge-chain))))

(defn blank-node []
  (node))

(defn node? [x]
  (instance? Node x))

(defn node-inputs [n] (:inputs n))
(defn node-outputs [n] (:outputs n))

(defn topology-built?
  "True when the node's EdgeChains have been realized."
  [n]
  (and (node? n)
       (not (ec/edge-chain-empty? (:inputs n)))
       (not (ec/edge-chain-empty? (:outputs n)))))

;; ---------------------------------------------------------------------------
;; PropagatorF — single pending value type
;;
;;   propagator-f — (fn [& inputs] value) or nil  (primitive activate)
;;   expand       — (fn [network] network) or nil (install children)
;;   input-ids    — intended inputs
;;   outputs      — intended output cell id(s)
;;   name         — dict key
;;
;; Primitive: f set, expand nil. Compound: expand set, f nil.
;; Both delay topology until the scheduler runs them with ready inputs.
;; ---------------------------------------------------------------------------

(defrecord PropagatorF [propagator-f expand input-ids outputs name])

(defn propagator-value?
  "Any pending/built propagator record."
  [x]
  (instance? PropagatorF x))

(defn propagator-f?
  "Primitive: has an activation `propagator-f`."
  [x]
  (boolean (and (propagator-value? x) (:propagator-f x))))

(defn compound-f?
  "Compound: has an `expand` installer (same record type)."
  [x]
  (boolean (and (propagator-value? x) (:expand x))))

(defn propagator-f
  "Construct a primitive propagator value (single output)."
  ([f input-ids output]
   (propagator-f f input-ids output output))
  ([f input-ids output name]
   (->PropagatorF f nil (vec input-ids) [output] name)))

(defn compound-f
  "Construct a compound propagator value (expand installs children)."
  ([expand input-ids outputs]
   (compound-f expand input-ids outputs (first outputs)))
  ([expand input-ids outputs name]
   (->PropagatorF nil expand (vec input-ids) (vec outputs) name)))

(defn propagator-output
  "Primary output cell (first of `outputs`)."
  [pf]
  (first (:outputs pf)))

;; ---------------------------------------------------------------------------
;; Cell — one content: nothing, or a concrete value
;; ---------------------------------------------------------------------------

(def nothing :prototype-2/nothing)

(defn nothing? [x]
  (or (nil? x) (= x nothing)))

(defrecord Cell [content])

(defn cell
  ([] (cell nothing))
  ([content] (->Cell (if (nil? content) nothing content))))

(defn cell? [x]
  (instance? Cell x))

(defn cell-content [c]
  (:content c))

(defn concrete-cell?
  "True when the cell holds a concrete (non-nothing) value."
  [c]
  (and (cell? c) (not (nothing? (cell-content c)))))
