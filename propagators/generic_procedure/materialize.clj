(ns propagators.generic-procedure.materialize
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.generic-procedure.constants :as constants]
            [propagators.generic-procedure.methods :as methods]
            [propagators.graph :as graph]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- copy-outer-cell
  ([n outer-net id]
   (copy-outer-cell n outer-net id identity))
  ([n outer-net id normalize]
   (cond
     (contains? (net/net-env n) id)
     n

     (contains? (net/net-env outer-net) id)
     (let [v (normalize (net/network-cell-strongest outer-net id))]
       (nb/install-cell n id v v))

     :else
     (nb/ensure-cell n id))))

(defn- producer-prop-ids
  [outer-net out-id]
  (let [g (net/net-graph outer-net)
        cell-node (get g out-id)]
    (->> (if cell-node (graph/node-input-ids cell-node) #{})
         (filter (fn [prop-id]
                   (let [prop-node (get g prop-id)]
                     (and prop-node
                          (prop/prop? (get (net/net-env outer-net) prop-id))
                          (contains? (graph/node-output-ids prop-node) out-id)
                          (not (contains? (graph/node-input-ids prop-node) out-id)))))))))

(defn- copy-producer-prop
  [n outer-net prop-id]
  (let [prop-node (graph/get-node (net/net-graph outer-net) prop-id)
        prop-value (net/network-lookup-propagator outer-net prop-id)
        n0 (reduce #(copy-outer-cell %1 outer-net %2)
                   n
                   (into (graph/node-input-ids prop-node)
                         (graph/node-output-ids prop-node)))]
    (-> n0
        (net/assoc-net-node prop-id prop-node)
        (net/assoc-net-prop prop-id prop-value))))

(defn- install-declared-slot
  [n collection-id [slot-key parent->declaration]]
  (reduce
   (fn [[acc prop-ids] parent-id]
     (let [[prop-id acc'] ((obj/p:legacy-slot slot-key parent-id collection-id) acc)]
       [acc' (conj prop-ids prop-id)]))
   [n []]
   (sort-by pr-str (keys parent->declaration))))

(defn- install-declared-slots
  [n collection-id declarations]
  (reduce
   (fn [[acc prop-ids] declaration]
     (let [[acc' prop-ids'] (install-declared-slot acc collection-id declaration)]
       [acc' (into prop-ids prop-ids')]))
   [n []]
   (sort-by (comp pr-str key) declarations)))

(defn- slot-declarations-for
  [outer-net collection-id]
  (merge-with merge
              (obj/slot-declarations-for outer-net collection-id)
              (obj/accessor-declarations-for outer-net collection-id)))

(defn- declared-method-branches
  [outer-net generic-id]
  (->> (slot-declarations-for outer-net generic-id)
       (keep (fn [[slot-key parent->declaration]]
               (when (constants/method-slot? slot-key)
                 {:slot-key slot-key
                  :method-key (second slot-key)
                  :branch-ids (sort-by pr-str (keys parent->declaration))})))
       (sort-by (comp pr-str :method-key))
       vec))

(defn- materialized-generic?
  [outer-net generic-id generic-value]
  (and (not (value/unusable? generic-value))
       (methods/compound-slot-present? generic-value constants/default-slot)
       (methods/compound-slot-present? generic-value constants/policy-slot)
       (= (count (methods/generic-methods generic-value))
          (count (declared-method-branches outer-net generic-id)))))

(defn- branch-field-parent-ids
  [outer-net branch-id]
  (->> (slot-declarations-for outer-net branch-id)
       vals
       (mapcat keys)
       (sort-by pr-str)
       vec))

(defn- materialize-branch
  [n outer-net branch-id]
  (let [declarations (slot-declarations-for outer-net branch-id)
        parent-ids (branch-field-parent-ids outer-net branch-id)
        n0 (nb/seed-cell (copy-outer-cell n outer-net branch-id)
                         branch-id
                         (obj/empty-compound-object))
        n1 (reduce #(copy-outer-cell %1 outer-net %2) n0 parent-ids)
        producer-ids (->> parent-ids
                          (mapcat #(producer-prop-ids outer-net %))
                          (sort-by pr-str)
                          vec)
        n2 (reduce #(copy-producer-prop %1 outer-net %2) n1 producer-ids)
        [n3 slot-prop-ids] (install-declared-slots n2 branch-id declarations)]
    {:net n3
     :prop-ids (into producer-ids slot-prop-ids)}))

(defn- materialize-method-branches
  [n outer-net generic-id]
  (reduce
   (fn [{:keys [net prop-ids]} {:keys [branch-ids]}]
     (reduce
      (fn [{:keys [net prop-ids]} branch-id]
        (let [{net' :net prop-ids' :prop-ids}
              (materialize-branch net outer-net branch-id)]
          {:net net'
           :prop-ids (into prop-ids prop-ids')}))
      {:net net :prop-ids prop-ids}
      branch-ids))
   {:net n :prop-ids []}
   (declared-method-branches outer-net generic-id)))

(defn materialize-generic-procedure
  "Materialize slot-declared generic procedure data into a readable value.

  This is intentionally local evaluation: it does not mutate `outer-net`, but it
  lets callers such as the cell protocol observe declaration-time slot topology
  without requiring eager activation of the generic initializer or handlers."
  [outer-net generic-id]
  (let [current-value (net/network-cell-strongest outer-net generic-id)]
    (if (materialized-generic? outer-net generic-id current-value)
      {:value current-value
       :updated? false}
      (let [method-branches (declared-method-branches outer-net generic-id)
            branch-ids (set (mapcat :branch-ids method-branches))
            generic-declarations (slot-declarations-for outer-net generic-id)
            generic-parent-ids (->> generic-declarations
                                    vals
                                    (mapcat keys)
                                    (sort-by pr-str)
                                    vec)
            n0 (copy-outer-cell net/empty-net outer-net generic-id methods/normalize-generic-value)
            n1 (reduce (fn [acc id]
                         (copy-outer-cell acc
                                          outer-net
                                          id
                                          #(if (and (value/nothing? %)
                                                    (contains? branch-ids id))
                                             (obj/empty-compound-object)
                                             %)))
                       n0
                       generic-parent-ids)
            {branch-net :net branch-prop-ids :prop-ids}
            (materialize-method-branches n1 outer-net generic-id)
            [slot-net generic-slot-prop-ids]
            (install-declared-slots branch-net generic-id generic-declarations)
            materialized-net (nb/run-propagators slot-net
                                                 (into branch-prop-ids
                                                       generic-slot-prop-ids))]
        {:value (net/network-cell-strongest materialized-net generic-id)
         :updated? true}))))
