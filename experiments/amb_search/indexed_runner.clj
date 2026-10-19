(ns experiments.amb-search.indexed-runner
  "Dedicated AMB experiment: directly reproject evidence on source withdrawal.
  The immutable index is Net-owned; cells and production execution are unchanged."
  (:require [clojure.set :as set]
            [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.datastructures.support-collection :as collection]
            [propagators.experimental.amb.observation :as observation]
            [propagators.experimental.ttms-branching :as branching]
            [propagators.helpers.task-queue :as queue]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.runner :as runner]
            [propagators.runner-constructor :as constructor]
            [propagators.stdlib.premise-state :as state]))

(def index-key ::index)

(defn- index-of [network]
  (or (net/network-dict-entry network index-key)
      {:by-source {} :by-cell {} :states {}}))

(defn- index-cell [network id]
  (let [content (net/network-cell-content network id)
        sources (if (collection/content? content) (collection/referenced-sources content) #{})
        index (index-of network)
        previous (get-in index [:by-cell id] #{})]
    (if (= previous sources)
      network
      (net/assoc-net-dict-entry
       network index-key
       (-> (reduce (fn [index source] (update-in index [:by-source source] disj id))
                   index (set/difference previous sources))
           (as-> index (reduce (fn [index source] (update-in index [:by-source source] (fnil conj #{}) id))
                               index (set/difference sources previous)))
           (assoc-in [:by-cell id] sources))))))

(defn initialize [network]
  (reduce (fn [current [id entry]]
            (if (cell/cell? entry) (index-cell current id) current))
          network (net/net-env network)))

(defn index-summary [network]
  (let [index (index-of network)]
    {:cells (count (:by-cell index))
     :sources (count (filter seq (vals (:by-source index))))
     :references (reduce + (map count (vals (:by-source index))))}))

(defn- project-withdrawal [network source entries stats]
  (let [affected (disj (get-in (index-of network) [:by-source source] #{}) source)
        update (collection/content {:premise-state entries})]
    (swap! stats update-in [:direct-projections] (fnil + 0) (count affected))
    (reduce (fn [[tasks current] id]
              (let [[fresh updated] (runner/apply-patch nil (message/message id update) current)
                    owner (net/network-lookup-cell updated id)
                    content (collection/retire-superseded (cell/cell-content owner))
                    retired (net/assoc-net-cell updated id
                                               (cell/cell (cell/cell-name owner) content
                                                          (merge/strongest-value content updated)))]
                [(queue/merge-queues tasks fresh) retired]))
            [queue/empty-queue network] (sort-by pr-str affected))))

(defn- withdrawal [before after id sources stats]
  (if (contains? sources id)
    (let [current (set (filter #(= id (:source %))
                               (state/states-of (net/network-cell-strongest after id))))
          previous (get-in (index-of before) [:states id] #{})
          tracked (net/assoc-net-dict-entry after index-key
                                          (assoc-in (index-of after) [:states id] current))]
      (if (and (not= previous current) (some #(= :retracted (:premises-status %)) current))
        (do (swap! stats update :withdrawals (fnil inc 0))
            (let [[tasks updated] (project-withdrawal tracked id current stats)]
              [tasks updated current]))
        [queue/empty-queue tracked nil]))
    [queue/empty-queue after nil]))

(defn- projected-bottom? [network id]
  (value/nothing? (observation/cell-base network id)))

(defn- derivation-complete? [built network entries id]
  (let [entry (net/network-lookup-propagator network id)
        original (net/network-lookup-propagator (:network built) id)
        node (get (net/net-graph network) id)
        original-node (get (net/net-graph (:network built)) id)]
    (and (prop/prop? original) (identical? (prop/prop-f original) (prop/prop-f entry))
         (= node original-node)
         (seq (:outputs node))
         (every? #(projected-bottom? network %) (:outputs node))
         (every? #(set/subset? entries (state/states-of (net/network-cell-strongest network %)))
                 (:outputs node))
         (case (prop/prop-name entry)
           :layered/apply (some #(projected-bottom? network %) (:inputs node))
           [::branching/branching :switch]
           (projected-bottom? network (get-in built [:cells :valid]))
           false))))

(defn- admit [complete? tasks stats]
  (loop [remaining tasks admitted queue/empty-queue]
    (if (queue/queue-empty? remaining)
      admitted
      (let [[id next-tasks] (queue/pop-task remaining)]
        (if (complete? id)
          (do (swap! stats update :avoided-derivations (fnil inc 0))
              (recur next-tasks admitted))
          (recur next-tasks (queue/enqueue admitted id)))))))

(defn- apply-one [built emitter operation network stats]
  (let [[tasks updated] (runner/apply-patch emitter operation network)]
    (if (message/message? operation)
      (let [id (message/message-id operation)
            indexed (index-cell updated id)
            [withdraw-tasks withdrawn entries] (withdrawal network indexed id
                                                  (set (get-in built [:cells :candidates])) stats)
            pending (queue/merge-queues tasks withdraw-tasks)]
        [(if entries
           (admit (partial derivation-complete? built withdrawn entries) pending stats)
           pending) withdrawn])
      [tasks updated])))

(defn- evaluate-patches [built stats]
  (fn [evaluated network {:keys [success fail]}]
    (try
      (let [{:keys [effects messages]} (patch/normalize-activation-return (:activation-result evaluated))
            [tasks updated]
            (reduce (fn [[tasks current] operation]
                      (let [[fresh next-network] (apply-one built (:emitter evaluated) operation current stats)]
                        [(queue/merge-queues tasks fresh) next-network]))
                    [queue/empty-queue network] (concat effects messages))]
        (success tasks updated))
      (catch Throwable error (fail error)))))

(defn run [built]
  (let [stats (atom {})
        advance (constructor/network-iterator-constructor
                 runner/fifo-task-policy runner/evaluate-propagator (evaluate-patches built stats))]
    (letfn [(bounce [{:keys [network] :as execution}]
              (fn []
                (advance execution
                         {:done (fn [final-network] {:status :completed :network final-network :index-stats @stats})
                          :fail (fn [error] {:status :failed :error error :network network :index-stats @stats})
                          :continue bounce})))]
      (trampoline bounce {:network (initialize (:network built)) :tasks (queue/into-queue (:tasks built))}))))
