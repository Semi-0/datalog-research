(ns propagators.prototype-2.construct
  "Pending propagator / compound constructors (value only, topology blank).

  Install auto-enqueues the new prop onto `Network.queue` (like core
  `install-propagator!`). The scheduler stays a separate function."
  (:require [propagators.prototype-2.network :as net]
            [propagators.prototype-2.types :as t]))

(defn pending-propagator-constructor
  "Install a pending propagator: `PropagatorF` in value, blank Node in topology.

  Registers `name` in Network.name (dict). Default name is the output cell —
  the first dependent of this propagator. Naming is constructor-only; build
  never touches the dict. Enqueues `prop-id` on the network queue."
  ([network]
   (fn [f]
     (fn
       ([ports] (pending-propagator-constructor network f ports))
       ([ports name] (pending-propagator-constructor network f ports name)))))
  ([network f ports]
   (pending-propagator-constructor network f ports (second ports)))
  ([network f ports name]
   (let [input-ids (vec (first ports))
         output (second ports)
         prop-id (net/new-prop-id)
         network (net/ensure-cells network (conj input-ids output))
         network (net/ensure-node network prop-id)
         pf (t/propagator-f f input-ids output name)]
     (-> network
         (net/assoc-value prop-id pf)
         (net/bind-prop-name name prop-id)
         (net/enqueue prop-id)))))

(defn pending-compound-constructor
  "Install a pending compound: same `PropagatorF` with `expand`, blank topology.

  Default dict name is the first output cell. Naming is constructor-only.
  Enqueues `prop-id` on the network queue.

  `expand` receives the network and returns it with inner pending props
  installed. Expansion runs at most once, and is skipped when the boundary
  inputs and outputs are already satisfied. If `expand` returns an unchanged
  network, the compound stays unbuilt."
  ([network expand input-ids outputs]
   (pending-compound-constructor network expand input-ids outputs (first outputs)))
  ([network expand input-ids outputs name]
   (let [input-ids (vec input-ids)
         outputs (vec outputs)
         prop-id (net/new-prop-id)
         network (net/ensure-cells network (into input-ids outputs))
         network (net/ensure-node network prop-id)
         cf (t/compound-f expand input-ids outputs name)]
     (-> network
         (net/assoc-value prop-id cf)
         (net/bind-prop-name name prop-id)
         (net/enqueue prop-id)))))
