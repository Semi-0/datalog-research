(ns propagators.datastructures.reducer-subnet
  "Declarative reducer subnet values.

  A reducer subnet is a pure strongest-value view over a source compound object:
  every usable public source slot becomes an update map and is folded through a
  merge network with fixed dict keys `:acc`, `:update`, and `:out`."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.cells.value :as value]
            [propagators.ids :as ids]
            [propagators.network :as net]))

(defn reducer-subnet
  [source merge-net init]
  {:source source
   :merge-net merge-net
   :init init})

(defn reducer-subnet?
  [x]
  (and (map? x)
       (= #{:source :merge-net :init} (set (keys x)))))

(defn source [x] (:source x))
(defn merge-net [x] (:merge-net x))
(defn init [x] (:init x))

(defn- internal-slot-key?
  [k]
  (or (= k :slot-index)
      (ids/node-id? k)
      (and (vector? k)
           (contains? #{:slot-sync :slot-tap :effect-tap} (first k)))))

(defn source-updates
  "All usable public source slots as `{:slot k :value v}` updates."
  [source-net]
  (if-not (net/network? source-net)
    []
    (->> (net/net-dict-or-empty source-net)
         (remove (fn [[k _]] (internal-slot-key? k)))
         (keep (fn [[slot-key slot-id]]
                 (when (contains? (net/net-env source-net) slot-id)
                   (let [slot-value (net/network-cell-strongest source-net slot-id)]
                     (when-not (value/unusable? slot-value)
                       {:slot slot-key :value slot-value})))))
         (sort-by (comp pr-str :slot))
         vec)))

(defn- dict-cell-id
  [n k]
  (or (net/network-dict-entry n k)
      (throw (ex-info "reducer merge-net missing required dict key"
                      {:key k :dict (net/net-dict-or-empty n)}))))

(defn- seed-round-cell
  [n cell-id v]
  (net/assoc-net-cell n cell-id (cell/cell v v)))

(defn- run-merge-round
  [merge-template acc update]
  (let [acc-id (dict-cell-id merge-template :acc)
        update-id (dict-cell-id merge-template :update)
        out-id (dict-cell-id merge-template :out)
        n0 (-> merge-template
               (seed-round-cell acc-id acc)
               (seed-round-cell update-id update)
               (seed-round-cell out-id value/nothing))
        tasks (pop-inputs [acc-id update-id] (net/net-graph n0))
        run-tasks (requiring-resolve 'propagators.core/run-tasks)
        after (run-tasks tasks n0)]
    (net/network-cell-strongest after out-id)))

(defn strongest
  [reducer-value]
  (let [merge-template (merge-net reducer-value)]
    (if-not (net/network? merge-template)
      value/contradiction
      (reduce
       (fn [acc update]
         (if (value/contradiction? acc)
           value/contradiction
           (run-merge-round merge-template acc update)))
       (init reducer-value)
       (source-updates (source reducer-value))))))
