(ns propagators.prototype-2.edge-chain
  "EdgeChain — origin dependences + last dependent only.

  origins — set of most upstream cells (inherited from upstream)
  tail    — set of last dependent cell(s) (the normal downstream cell)

  On every new node build: origins = union of upstream cells' origins
  (or the upstream cell itself if it has none yet); tail = this downstream.
  Never stores intermediate nodes. Composing A→B with B→C yields A→C.")

(defrecord EdgeChain [origins tail])

(defn- as-id-set [xs]
  (cond
    (set? xs) xs
    (nil? xs) #{}
    (or (sequential? xs) (set? xs)) (set xs)
    :else #{xs}))

(defn edge-chain
  [origins tail]
  (->EdgeChain (as-id-set origins) (as-id-set tail)))

(defn empty-edge-chain []
  (edge-chain #{} #{}))

(defn edge-chain? [x]
  (instance? EdgeChain x))

(defn edge-chain-empty?
  [chain]
  (and (edge-chain? chain)
       (empty? (:origins chain))
       (empty? (:tail chain))))

(defn edge-chain-origins [chain] (:origins chain))
(defn edge-chain-heads [chain] (:origins chain))
(defn edge-chain-tail [chain] (:tail chain))
