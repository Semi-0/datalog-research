(ns propagators.prototype-2.network
  "Network record, lookups, readiness, ensure helpers, and task queue.

  topology — id -> Node
  value    — id -> Cell | PropagatorF
  name     — symbol/keyword -> prop id
  queue    — FIFO of prop ids awaiting the scheduler (not the scheduler itself)"
  (:require [propagators.prototype-2.types :as t]))

(def empty-queue
  "Empty FIFO task queue carried by the network."
  clojure.lang.PersistentQueue/EMPTY)

(defrecord Network [topology value name queue])

(defn network
  ([topology value]
   (network topology value {} empty-queue))
  ([topology value name]
   (network topology value name empty-queue))
  ([topology value name queue]
   (->Network (or topology {}) (or value {}) (or name {})
              (or queue empty-queue))))

(def empty-network (network {} {} {} empty-queue))

(defn network? [x]
  (instance? Network x))

(defn network-topology [n] (:topology n))
(defn network-value [n] (:value n))
(defn network-name [n] (:name n))
(defn network-queue [n] (:queue n))

(defn network-with-topology [n topology]
  (network topology (network-value n) (network-name n) (network-queue n)))

(defn network-with-value [n value]
  (network (network-topology n) value (network-name n) (network-queue n)))

(defn network-with-name [n name]
  (network (network-topology n) (network-value n) name (network-queue n)))

(defn network-with-queue [n queue]
  (network (network-topology n) (network-value n) (network-name n) queue))

(defn enqueue
  "Append one prop id to the network queue (core-style install alert)."
  [n prop-id]
  (network-with-queue n (conj (network-queue n) prop-id)))

(defn enqueue-all
  "Append many prop ids to the network queue."
  [n prop-ids]
  (reduce enqueue n prop-ids))

(defn assoc-node [n id node]
  (network-with-topology n (assoc (network-topology n) id node)))

(defn assoc-value [n id entry]
  (network-with-value n (assoc (network-value n) id entry)))

(defn bind-name [n nm id]
  (network-with-name n (assoc (network-name n) nm id)))

(defn lookup-name
  "Resolve dict `nm` to a node/prop id, or nil."
  [network nm]
  (get (network-name network) nm))

(defn bind-prop-name
  "Register prop under `nm` if unused — first dependent propagator wins."
  [network nm prop-id]
  (if (contains? (network-name network) nm)
    network
    (bind-name network nm prop-id)))

(defn lookup-node [network id]
  (get (network-topology network) id))

(defn lookup-value
  [network id]
  (get (network-value network) id))

(defn cell-value
  "Single cell content at `id`: `nothing` or a concrete value."
  [network id]
  (let [v (lookup-value network id)]
    (if (t/cell? v)
      (t/cell-content v)
      t/nothing)))

(defn inputs-ready?
  [network input-ids]
  (every? (fn [id] (not (t/nothing? (cell-value network id))))
          input-ids))

(defn outputs-satisfied?
  "True when every output cell already holds a concrete value."
  [network output-ids]
  (every? (fn [id] (not (t/nothing? (cell-value network id))))
          output-ids))

(defn boundary-satisfied?
  "Smart-skip predicate: all inputs and all outputs are concrete."
  [network input-ids output-ids]
  (and (inputs-ready? network input-ids)
       (outputs-satisfied? network output-ids)))

(defn ensure-cell
  "Blank topology node + Cell with content nothing, when absent."
  [network id]
  (cond-> network
    (not (contains? (network-topology network) id))
    (assoc-node id (t/blank-node))
    (not (t/cell? (lookup-value network id)))
    (assoc-value id (t/cell t/nothing))))

(defn ensure-node
  "Topology node only (no cell). Prefer `ensure-cell` for input/output cells."
  [network id]
  (if (contains? (network-topology network) id)
    network
    (assoc-node network id (t/blank-node))))

(defn ensure-cells [network ids]
  (reduce ensure-cell network ids))

(defn ensure-nodes [network ids]
  (reduce ensure-node network ids))

(defn new-prop-id []
  (keyword (str "prop-" (java.util.UUID/randomUUID))))

(defn new-cell-id [label]
  (keyword (str label "-" (java.util.UUID/randomUUID))))
