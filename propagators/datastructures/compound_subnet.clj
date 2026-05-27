(ns propagators.datastructures.compound_subnet
  "Compound linked-list subnet state and merge (no propagators, no scheduler)."
  (:require [propagators.cells.avatar :as avatar]
            [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.datastructures.compound_strongest_result :as strongest]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.datastructures.compound_update :as update]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.ids :as id]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib :refer [hook-output-taps]]))

(defn subnet-outer-ids
  "All outer node-id dict keys in `subnet`."
  [subnet]
  (let [dict (net/net-dict-or-empty subnet)]
    (vec (filter id/node-id? (keys dict)))))

(defn subnet-effectful-tasks
  "Tasks to run avatar outputs on `subnet` for `outer-ids`, after hooking taps."
  [subnet outer-ids updated*]
  (let [subnet' (hook-output-taps subnet outer-ids updated*)
        dict (net/net-dict-or-empty subnet')
        avatar-ids (mapv #(get dict %) (vec outer-ids))]
    {:subnet subnet'
     :tasks (pop-inputs avatar-ids (net/net-graph subnet'))}))

(defn run-subnet-effectful
  "Run internal subnet for compound state; return continuation with `:updated*` frontier.
  Called only from `c:linked-list`, not from `strongest-value`."
  [state]
  (let [run-tasks (requiring-resolve 'propagators.core/run-tasks)
        subnet (state/state-subnet state)
        out-ids (state/state-out-ids state)
        outer-ids (vec out-ids)
        updated* (atom #{})
        {:keys [subnet tasks]} (subnet-effectful-tasks subnet outer-ids updated*)]
    (strongest/subnet-continuation (run-tasks tasks subnet) updated* out-ids)))

(defn update-internal-network [subnet outer-net name update]
  (let [dict (net/net-dict-or-empty subnet)
        id (update/compound-id update)
        strongest (net/network-cell-strongest outer-net id)
        content (net/network-cell-content outer-net id)
        spawn (fn [n key] (second (avatar/spawn-avatar-cell n key strongest content)))]
    (if (contains? dict name)
      (if (contains? dict id)
        (avatar/sync-avatar-cell subnet outer-net id)
        (-> subnet
            (spawn id)
            (avatar/link-avatars id name)))
      (-> subnet
          (spawn name)
          (spawn id)
          (avatar/link-avatars id name)))))

(defn merge-compound-data
  "Merge compound update into state; extend avatars and accumulate output ids.
  Does not run the internal network."
  [compound-state update network]
  (let [subnet (state/state-subnet compound-state)
        out-ids (state/state-out-ids compound-state)
        subnet' (cond
                  (update/complete-compound-data? update) (-> subnet
                                                              (update-internal-network network :tail (update/take-tail update))
                                                              (update-internal-network network :head (update/take-head update)))
                  (update/compound-data-tail? update) (update-internal-network subnet network :tail update)
                  (update/compound-data-head? update) (update-internal-network subnet network :head update)
                  :else subnet)
        out-ids' (into out-ids (update/compound-data-ids update))]
    (state/compound-state subnet' out-ids')))

(defn- merge-graph-node
  [target source inner-id]
  (if-let [src-node (get (net/net-graph source) inner-id)]
    (if-let [dst-node (get (net/net-graph target) inner-id)]
      (net/net-with-graph
       target
       (assoc (net/net-graph target)
              inner-id
              (graph/node (into (graph/node-input-ids dst-node) (graph/node-input-ids src-node))
                          (into (graph/node-output-ids dst-node) (graph/node-output-ids src-node)))))
      (net/net-with-graph target (assoc (net/net-graph target) inner-id src-node)))
    target))

(defn- install-source-entry
  [target source inner-id]
  (if-let [entry (get (net/net-env source) inner-id)]
    (-> target
        (merge-graph-node source inner-id)
        (net/net-with-env (assoc (net/net-env target) inner-id entry)))
    target))

(defn- merge-entry
  [target source target-inner source-inner network]
  (let [merge-cell (requiring-resolve 'propagators.cells.merge/cell-merge)
        strongest-value (requiring-resolve 'propagators.cells.merge/strongest-value)
        source-entry (get (net/net-env source) source-inner)
        target-entry (get (net/net-env target) target-inner)]
    (cond
      (nil? source-entry) target
      (nil? target-entry) (-> target
                              (merge-graph-node source source-inner)
                              (net/net-with-env (assoc (net/net-env target) source-inner source-entry)))
      (and (cell/cell? target-entry) (cell/cell? source-entry))
      (let [content' (merge-cell (cell/cell-content target-entry) (cell/cell-content source-entry) network)]
        (if (value/contradiction? content')
          value/contradiction
          (let [strongest' (strongest-value content' network)]
            (-> target
                (merge-graph-node source target-inner)
                (net/net-with-env (assoc (net/net-env target)
                                         target-inner
                                         (cell/cell content' strongest')))))))
      (and (prop/prop? target-entry) (prop/prop? source-entry))
      (if (= target-entry source-entry)
        (merge-graph-node target source target-inner)
        value/contradiction)
      :else value/contradiction)))

(defn merge-compound-sync
  "Merge incoming compound subnet by slot. Contradiction on slot/propagator conflicts."
  [compound-state sync network]
  (let [target (state/state-subnet compound-state)
        source (update/sync-subnet sync)
        source-dict (net/net-dict-or-empty source)
        target-dict (net/net-dict-or-empty target)
        slots (let [s (update/sync-slots sync)]
                (if (seq s) s (subnet-outer-ids source)))
        out-ids (into (state/state-out-ids compound-state) slots)]
    (loop [merged target
           dict target-dict
           remaining slots]
      (if (empty? remaining)
        (state/compound-state (net/net-with-dict merged dict) out-ids)
        (let [outer-id (first remaining)
              source-inner (get source-dict outer-id)
              target-inner (get dict outer-id)]
          (cond
            (nil? source-inner)
            (recur merged dict (rest remaining))
            (nil? target-inner)
            (let [merged' (install-source-entry merged source source-inner)]
              (recur merged' (assoc dict outer-id source-inner) (rest remaining)))
            (not= target-inner source-inner)
            value/contradiction
            :else
            (let [merged' (merge-entry merged source target-inner source-inner network)]
              (if (value/contradiction? merged')
                value/contradiction
                (recur merged' dict (rest remaining))))))))))

(defn dispatch-target?
  "Compound sync no longer filters nested collection cells."
  [_network _outer-id]
  true)

