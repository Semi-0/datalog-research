(ns propagators.semantic-relationships
  "Indexed semantic relationships between propagator occurrences."
  (:require [propagators.cells.cell :as cell]
            [propagators.graph :as graph]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def default-max-depth 32)
(def spawned-relation :propagator/spawned)

(defn semantic-node-key
  [network-path propagator-id]
  [network-path propagator-id])

(def empty-store
  {:entities {}
   :relations {spawned-relation {}}
   :present-at-start #{}})

(defn- propagator-entries
  [network path]
  (into {}
        (keep
         (fn [[id entry]]
           (when (prop/prop? entry)
             (let [key (semantic-node-key path id)]
               [key {:propagator-id id
                     :propagator-kind (prop/prop-name entry)
                     :network-path path}]))))
        (net/net-env network)))

(defn- nested-networks
  [network]
  (keep
   (fn [[id entry]]
     (when (cell/cell? entry)
       (let [strongest (cell/cell-strongest entry)]
         (when (net/net? strongest)
           [id strongest]))))
   (net/net-env network)))

(defn inventory
  ([network] (inventory network default-max-depth))
  ([network max-depth]
   (when (neg? max-depth)
     (throw (ex-info "max relationship depth must be non-negative"
                     {:max-depth max-depth})))
   (letfn [(walk [current path depth]
             (let [local (propagator-entries current path)]
               (if (< depth max-depth)
                 (reduce
                  (fn [entries [cell-id nested]]
                    (merge entries
                           (walk nested
                                 (conj path [:cell cell-id])
                                 (inc depth))))
                  local
                  (nested-networks current))
                 local)))]
     (walk network [:outer] 0))))

(defn from-network
  ([network] (from-network network default-max-depth))
  ([network max-depth]
   (let [entities (inventory network max-depth)
         nodes (into {} (map (fn [key] [key (graph/blank-node)]))
                     (keys entities))]
     {:entities entities
      :relations {spawned-relation nodes}
      :present-at-start (set (keys entities))})))

(defn add-spawn
  [store parent child]
  (let [parent-key (semantic-node-key (:network-path parent)
                                      (:propagator-id parent))
        child-key (semantic-node-key (:network-path child)
                                     (:propagator-id child))
        relationship-graph (-> (get-in store [:relations spawned-relation] {})
                               (graph/ensure-node parent-key)
                               (graph/ensure-node child-key)
                               (graph/link-edge parent-key child-key))]
    (-> store
        (assoc-in [:entities parent-key] parent)
        (assoc-in [:entities child-key] child)
        (assoc-in [:relations spawned-relation] relationship-graph))))

(defn spawned-children
  [store key]
  (if-let [node (get-in store [:relations spawned-relation key])]
    (graph/node-output-ids node)
    #{}))

(defn spawned-parents
  [store key]
  (if-let [node (get-in store [:relations spawned-relation key])]
    (graph/node-input-ids node)
    #{}))

(defn- merge-node
  [left right]
  (graph/node (into (graph/node-input-ids left)
                    (graph/node-input-ids right))
              (into (graph/node-output-ids left)
                    (graph/node-output-ids right))))

(defn merge-stores
  ([] empty-store)
  ([left] left)
  ([left right]
   {:entities (merge (:entities left) (:entities right))
    :relations
    {spawned-relation
     (merge-with merge-node
                 (get-in left [:relations spawned-relation] {})
                 (get-in right [:relations spawned-relation] {}))}
    :present-at-start
    (into (:present-at-start left) (:present-at-start right))})
  ([left right & more]
   (reduce merge-stores (merge-stores left right) more)))

(defn spawned
  ([before after] (spawned before after default-max-depth))
  ([before after max-depth]
   (let [before-entities (inventory before max-depth)
         after-entities (inventory after max-depth)]
     (->> (keys after-entities)
          (remove #(contains? before-entities %))
          (sort-by pr-str)
          (mapv after-entities)))))
