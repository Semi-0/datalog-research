(ns propagators.stdlib
  (:require [propagators.cells.value :as value]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.arithmetic :as arithmetic]
            [propagators.stdlib.arithmetic.base :as base]
            [propagators.stdlib.arithmetic.provenance :as provenance]))

(def p:id (prop/primitive-propagator (fn [x] x)))

(def p:+ base/+)

(def p:switch
  (prop/primitive-propagator
   (fn [x enabled?]
     (if enabled? x value/nothing))))

(def p:provenance-union provenance/p:union)

(def arithmetic-base-closure base/arithmetic-base-closure)

(def arithmetic-provenance-closure provenance/arithmetic-provenance-closure)

(def plus-base-closure base/plus-closure)

(def plus-provenance-closure provenance/+)

(def procedure-extension arithmetic/procedure-extension)

(def plus-base-extension arithmetic/plus-base-extension)

(def plus-provenance-extension arithmetic/plus-provenance-extension)

(defn p:layered+
  "Create a layered + propagator installer backed by `procedure-id`."
  [procedure-id]
  (arithmetic/layered-operator procedure-id))

(defn fast-bi-sync
  "Install bidirectional `p:id` sync between `a` and `b`.

  Returns `[[a->b b->a] network]`, where the ids are the installed propagators."
  [network a b]
  (let [[a->b n] ((p:id a b) network)
        [b->a n] ((p:id b a) n)]
    [[a->b b->a] n]))

(def effect:tap
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

;; Map shape matches `Closure` record accessors (`:f`, `:net`); avoids stdlib ↔ closure cycle.
(def bi-sync-closure {:f bi-sync :net net/empty-net})
