(ns propagators.stdlib
  (:require [propagators.network :as net]
            [propagators.propagator :as prop]
            ))

(def p:id (prop/primitive-propagator (fn [x] x)))

(def p:tap
  (fn [do-something]
    (fn [in]
      (prop/construct-propagator
       (fn [_inputs _outputs _network]
         (do-something _inputs)
         [])
       [in]
       []))))

(defn mark-updated-tap
  "Build an effectful tap that records `outer-node-id` into `updated*`."
  [updated* outer-node-id]
  (p:tap
   (fn [_inputs]
     (swap! updated* conj outer-node-id))))

(defn hook-output-taps
  "Install taps on avatar cells whose dict key is in `outer-ids` (once per key)."
  [subnet outer-ids updated*]
  (reduce
   (fn [n outer-id]
     (let [dict (net/net-dict-or-empty n)
           hooked (or (:tap-hooked dict) #{})]
       (if (contains? hooked outer-id)
         n
         (if-let [avatar-id (get dict outer-id)]
           (let [n' (net/install-net n ((mark-updated-tap updated* outer-id) avatar-id))
                 dict' (net/net-dict-or-empty n')]
             (net/net-with-dict n' (assoc dict' :tap-hooked (conj hooked outer-id))))
           n))))
   subnet
   (vec outer-ids)))

;; Topology-only link: wires ports without merge/messages when the propagator runs.
(def p:nothing (fn [a b] (prop/construct-propagator (fn [_inputs _outputs _network] []) [a] [b])))

(defn nothing-out-link
  "Boundary topology: avatar → real (compound output side)."
  [net real avatar]
  (second ((p:nothing avatar real) net)))

(defn nothing-in-link
  "Boundary topology: real → avatar (compound input side)."
  [net real avatar]
  (second ((p:nothing real avatar) net)))

;; Boundary input/output nodes are the same constraint cells (typically two).
;; Cross-sync: each input boundary feeds the opposite output (avatar) port via `p:id`.
(defn bi-sync
  [_closure-struct input-nodes output-nodes network]
  (let [[n-a n-b] (vec input-nodes)
        [out-a out-b] (vec output-nodes)]
    (reduce (fn [n [from to]]
              (second ((p:id from to) n)))
            network
            [[n-a out-b] [n-b out-a]])))

(def bi-sync-closure [bi-sync net/empty-net])
