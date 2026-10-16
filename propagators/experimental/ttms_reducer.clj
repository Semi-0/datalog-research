(ns propagators.experimental.ttms-reducer
  "Opt-in recorded history on reducer-cell, with a dedicated TTMS recording source."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.datastructures.support :as support]
            [propagators.datastructures.timestamp :as timestamp]
            [propagators.experimental.ttms-compound :as compound]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.observation :as observation]))

(defn merge-history [content update]
  {:history/observations (set/union (get content :history/observations #{})
                                    (get update :history/observations #{}))
   :history/states (support/join (get content :history/states #{})
                                 (get update :history/states #{}))})

(defn- record-order [{:keys [base support]}]
  [(mapv (fn [{:keys [source timestamp premises-status]}]
           [(pr-str source) (timestamp/time-rank timestamp) (pr-str premises-status)])
         (sort-by (comp pr-str :source) support))
   (pr-str base)])

(defn project-history
  "Keep historical provenance separate from the support of currently valid records.
  Ordering is by source, then source timestamp; this is not global arrival order."
  [slots]
  (let [records (get slots :history/observations #{})
        frontier (get slots :history/states #{})
        current (compound/current-observations records frontier)
        ordered (vec (sort-by record-order records))]
    {:records (mapv #(assoc % :current? (contains? current %)) ordered)
     :history (mapv :base ordered)
     :current (into [] (comp (filter current) (map :base)) ordered)
     :support (apply support/combine (map :support current))
     :frontier frontier}))

(defn- template [input-names output-names calculate]
  (let [names (into (vec input-names) output-names)
        ports (zipmap names (repeatedly (count names) ids/new-node-id))
        initial (reduce-kv net/assoc-net-dict-entry
                          (nb/install-cells (vals ports)) ports)
        [_ installed]
        ((prop/construct-propagator
          ::template
          (fn [_inputs _outputs network]
            (let [inputs (into {} (map (fn [k]
                                         [k (net/network-cell-strongest network (ports k))]))
                               input-names)
                  outputs (calculate inputs)]
              (mapv (fn [k] (message (ports k) (get outputs k))) output-names)))
          (mapv ports input-names) (mapv ports output-names))
         initial)]
    installed))

(defn- merge-template []
  (template [:content :update] [:out]
            (fn [{:keys [content update]}]
              {:out (merge-history content update)})))

(defn- projection-template []
  (template [:slots] [:out :dependence :epoch]
            (fn [{:keys [slots]}]
              (let [result (project-history slots)]
                {:out result :dependence (:support result) :epoch (:frontier result)}))))

(defn p:collect
  "Install input -> reducer history -> recording source -> output.
  The caller supplies four distinct, installed cells. Archive is an exclusively
  owned injection cell. Only an actual change to recorded state advances its epoch."
  [input-id history-id archive-id output-id]
  (if-not (= 4 (count (set [input-id history-id archive-id output-id])))
    (throw (ex-info "History collection requires four distinct cells" {}))
    (let [merge-net (merge-template)
          strongest-net (projection-template)
          record (prop/construct-propagator
                  ::record
                  (fn [_inputs _outputs network]
                    (let [source (compound/evidence (net/network-cell-content network input-id))
                          slots {:history/observations (:support/observations source)
                                 :history/states (:support/states source)}]
                      [(message history-id (reducer/reducer-cell
                                            history-id merge-net strongest-net slots))]))
                  [input-id] [history-id])
          archive (publication/p:observe
                   (ids/new-node-id) history-id archive-id
                   (fn [network]
                     (let [v (net/network-cell-strongest network history-id)]
                       (if (value/nothing? v)
                         value/nothing
                         (reducer/reduced-result v)))))
          forward (observation/identity ::recorded-history archive-id output-id)]
      (fn [network]
        (let [[record-id n1] (record network)
              [archive-prop n2] (archive n1)
              [forward-id n3] (forward n2)]
          [[record-id archive-prop forward-id] n3])))))
