(ns propagators.prototype-2.evaluate
  "One evaluate path: ensure topology (wire or expand), then maybe activate f.

  Scheduler is `run-tasks` (drains Network.queue). Install/write enqueue."
  (:require [propagators.prototype-2.edge-chain :as ec]
            [propagators.prototype-2.network :as net]
            [propagators.prototype-2.types :as t]))

(defn origins-of
  "Origin set of `cell-id`: inherit upstream origins, else #{cell-id}."
  [network cell-id]
  (let [chain (some-> (net/lookup-node network cell-id) t/node-inputs)]
    (if (and chain (not (ec/edge-chain-empty? chain)))
      (ec/edge-chain-origins chain)
      #{cell-id})))

(defn compose-edge-chain
  "New node chain: inherit union of upstream origin sets; tail = downstream."
  [network input-ids output]
  (ec/edge-chain
   (into #{} (mapcat #(origins-of network %) input-ids))
   #{output}))

(defn publish-dependence
  "Store composed origins→tail on the downstream cell's input EdgeChain."
  [network output chain]
  (let [n (or (net/lookup-node network output) (t/blank-node))]
    (net/assoc-node network output (t/node chain (t/node-outputs n)))))

(defn mark-built
  "Wire `prop-id` with composed origins→tails covering `output-ids`."
  [network prop-id input-ids output-ids]
  (let [dep (compose-edge-chain network input-ids (first output-ids))
        dep (ec/edge-chain (ec/edge-chain-origins dep) (set output-ids))
        wired (t/node dep dep)]
    (reduce (fn [n out]
              (publish-dependence n out (ec/edge-chain (ec/edge-chain-origins dep) #{out})))
            (net/assoc-node network prop-id wired)
            output-ids)))

(defn ensure-topology
  "Delay topology once: expand (if any) or mark EdgeChains when inputs ready.

  Shared by primitive and compound — same readiness / once-built / smart-skip."
  [network prop-id]
  (let [pf (net/lookup-value network prop-id)
        n (net/lookup-node network prop-id)
        inputs (:input-ids pf)
        outs (:outputs pf)]
    (cond
      (not (t/propagator-value? pf))
      network

      (and n (t/topology-built? n))
      network

      (not (net/inputs-ready? network inputs))
      network

      (net/boundary-satisfied? network inputs outs)
      network

      (:expand pf)
      (let [network' ((:expand pf) network)]
        (if (= network network')
          network
          (mark-built network' prop-id inputs outs)))

      :else
      (mark-built network prop-id inputs outs))))

;; compat aliases
(def build-propagator-topology ensure-topology)
(def expand-compound ensure-topology)

(defn propagator-tasks-for
  "Propagator ids whose intended inputs include `cell-id`."
  [network cell-id]
  (into []
        (keep (fn [[prop-id entry]]
                (when (and (t/propagator-value? entry)
                           (some #{cell-id} (:input-ids entry)))
                  prop-id)))
        (net/network-value network)))

(defn write-cell
  "Set cell content and enqueue listening props onto Network.queue."
  [network id v]
  (let [network (net/ensure-cell network id)
        network (net/assoc-value network id (t/cell v))]
    (net/enqueue-all network (propagator-tasks-for network id))))

(defn evaluate-propagator
  "Ensure topology; if primitive `f` present and built, activate into first out."
  [network prop-id]
  (let [v (net/lookup-value network prop-id)]
    (if-not (t/propagator-value? v)
      network
      (let [outs (:outputs v)
            inputs (:input-ids v)]
        (if (net/boundary-satisfied? network inputs outs)
          network
          (let [network (ensure-topology network prop-id)
                n (net/lookup-node network prop-id)]
            (if-not (t/topology-built? n)
              network
              (if-let [f (:propagator-f v)]
                (let [output (t/propagator-output v)
                      input-vals (mapv #(net/cell-value network %) inputs)
                      result (apply f input-vals)
                      before (net/cell-value network output)]
                  (if (= before result)
                    network
                    (write-cell network output result)))
                network))))))))

(def empty-stream
  "Alias for network empty queue (compat)."
  net/empty-queue)

(defn run-tasks
  "Scheduler: FIFO-drain `Network.queue` (optionally enqueue `tasks` first)."
  ([network]
   (run-tasks network nil))
  ([network tasks]
   (loop [network (if (seq tasks)
                    (net/enqueue-all network tasks)
                    network)]
     (let [q (net/network-queue network)]
       (if (empty? q)
         network
         (let [prop-id (peek q)
               network (net/network-with-queue network (pop q))
               network (evaluate-propagator network prop-id)]
           (recur network)))))))
