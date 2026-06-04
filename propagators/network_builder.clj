(ns propagators.network-builder
  "Small construction helpers for immutable propagator networks."
  (:require [propagators.builder-policy :as policy]
            [propagators.cells.cell :as cell]
            [propagators.core :as core]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn install-cell
  "Install a cell into `n`, returning the updated network."
  ([n id]
   (net/install-net n (cell/construct-cell id)))
  ([n id content strongest]
   (net/install-net n (cell/construct-cell id content strongest))))

(defn install-cells
  "Install empty cells for every id into a fresh network."
  [ids]
  (reduce install-cell net/empty-net ids))

(defn seed-cell
  "Set `id` to a cell whose content and strongest value are both `v`."
  [n id v]
  (net/assoc-net-cell n id (cell/cell v v)))

(defn install-propagator
  "Install a propagator with `installer`, returning `[prop-id updated-network]`.

  When `*builder-policy*` is `:queue`, also pass `tasks` and use
  `install-propagator*` to enqueue without running."
  [n installer]
  (installer n))

(defn install-propagator*
  "Install per `propagators.builder-policy/*builder-policy*`.

  Returns `[prop-id network tasks]`. `:lazy` leaves `tasks` unchanged;
  `:queue` enqueues `prop-id` without running."
  [n tasks installer]
  (let [[prop-id n'] (install-propagator n installer)]
    (if (policy/policy-queue?)
      [prop-id n' (tq/enqueue tasks prop-id)]
      [prop-id n' tasks])))

(defn run-queued-tasks
  "Drain `tasks` on `n`; returns `[network' empty-queue]`."
  [n tasks]
  [(core/run-tasks tasks n) tq/empty-queue])

(defn run-propagators
  "Run propagators by id on `n` (immediate, not policy-aware)."
  [n prop-ids]
  (core/run-tasks (tq/enqueue-all tq/empty-queue prop-ids) n))

(defn run-propagators-quiesce
  "Run `prop-ids` then drain the task queue until empty."
  [n prop-ids]
  (core/run-tasks (tq/enqueue-all tq/empty-queue prop-ids) n))

(defn install-propagator!
  "Install a propagator and enqueue it (explicit queue, ignores policy)."
  [n tasks installer]
  (let [[prop-id n'] (install-propagator n installer)]
    [n' (tq/enqueue tasks prop-id)]))

(defn neighbor-propagator-ids
  "Propagator ids adjacent to cell `id` in `n`."
  [n id]
  (when-let [node (get (net/net-graph n) id)]
    (filter #(prop/prop? (get (net/net-env n) %))
            (into (vec (:inputs node)) (:outputs node)))))

(defn seed-cell*
  "Seed per `*builder-policy*`. Returns `[network tasks]`."
  [n tasks id v]
  (let [n' (seed-cell n id v)]
    (if (policy/policy-queue?)
      [n' (tq/enqueue-all tasks (neighbor-propagator-ids n' id))]
      [n' tasks])))

(defn seed-cell!
  "Seed a cell and enqueue neighboring propagators (explicit queue)."
  [n tasks id v]
  (seed-cell* n tasks id v))

(defn add-named-cell
  "Install a named cell under dict key `k` with content and strongest `v`."
  [n k v]
  (let [id (ids/new-node-id)
        n' (install-cell n id v v)]
    (net/assoc-net-dict-entry n' k id)))

(defn named-cell-net
  "Build a fresh network from `[dict-key value]` pairs."
  [named-values]
  (reduce
   (fn [n [k v]]
     (add-named-cell n k v))
   net/empty-net
   named-values))
