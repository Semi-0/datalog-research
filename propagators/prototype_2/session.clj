(ns propagators.prototype-2.session
  "tell, dependence, chain completeness."
  (:require [propagators.prototype-2.edge-chain :as ec]
            [propagators.prototype-2.evaluate :as ev]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.types :as t]))

(defn satisfied?
  "True when `id` holds a concrete cell value."
  [network id]
  (not (t/nothing? (net/cell-value network id))))

(defn dependence-of
  "Published EdgeChain on `terminal` (origins + tail), or nil."
  [network terminal]
  (let [chain (some-> (net/lookup-node network terminal) t/node-inputs)]
    (when (and chain (not (ec/edge-chain-empty? chain)))
      chain)))

(defn first-dependences
  "Origin set for `terminal` from its published chain.

  Every cell that participates in dependence must already carry origins;
  missing roots are not allowed."
  [network terminal]
  (if-let [chain (dependence-of network terminal)]
    (ec/edge-chain-origins chain)
    (throw (ex-info "cell has no published origins"
                    {:terminal terminal}))))

(defn tell
  "Set cell `id` content to concrete `v` (or `nothing`).

  A freshly told cell with no inherited chain becomes an origin:
  origins = #{id}, tail = #{id}. Listeners are enqueued onto Network.queue.
  Returns `[alerted-prop-ids network]` for inspection; scheduler drains the queue."
  [network id v]
  (let [network (net/ensure-cell network id)
        network (if (dependence-of network id)
                  network
                  (ev/publish-dependence network id (ec/edge-chain #{id} #{id})))
        network (ev/write-cell network id v)
        alerted (ev/propagator-tasks-for network id)]
    [alerted network]))

(defn chain-complete?
  "True when the terminal (tail) is satisfied and every origin is.

  `terminal-or-chain` may be a terminal cell id, or an explicit EdgeChain."
  [network terminal-or-chain]
  (if (ec/edge-chain? terminal-or-chain)
    (let [{:keys [origins tail]} terminal-or-chain]
      (and (every? #(satisfied? network %) tail)
           (every? #(satisfied? network %) origins)))
    (and (satisfied? network terminal-or-chain)
         (every? #(satisfied? network %)
                 (first-dependences network terminal-or-chain)))))
