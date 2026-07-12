(ns propagators.stdlib.effect
  "Effectful tap helpers for subnet execution."
  (:require [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn effect:tap
  [do-something]
  (fn [in]
    (prop/construct-propagator
     :stdlib/effect-tap
     (fn [_inputs _outputs _network]
       (do-something _inputs)
       [])
     [in]
     [])))

(defn mark-updated-tap
  "Build an effectful tap that records `outer-node-id` into `updated*`."
  [updated* outer-node-id]
  (effect:tap
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
