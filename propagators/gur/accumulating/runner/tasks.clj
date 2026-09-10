(ns propagators.gur.accumulating.runner.tasks
  "Task selection and per-run prop state cache for the accumulating GUR runner."
  (:require [propagators.cells.cell :as cell]
            [clojure.set :as set]
            [propagators.core :as core]
            [propagators.graph :as graph]
            [propagators.gur.accumulating.facts :as facts]
            [propagators.gur.accumulating.runner.instrumentation :as instr]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- network-prop-ids
  [n]
  (->> (net/net-env n)
       (keep (fn [[id entry]]
               (cond
                 (prop/prop? entry)
                 id

                 :else
                 nil)))
       set))

(defn- pending-sort-key
  [task-key index]
  ;; Boundary tasks use the current declared prop index. Run declaration tasks
  ;; first so a boundary index does not get consumed before the props it should
  ;; wake have been added.
  [(if (= :boundary (first task-key)) 1 0)
   (pr-str task-key)
   (pr-str index)])

(defn- earlier-pending?
  [a b]
  (or (nil? b)
      (neg? (compare (:sort-key a) (:sort-key b)))))

(defn next-pending-task-fact
  [n task-cursor]
  (let [best (reduce (fn [best [task-key {:keys [indexes] :as entry}]]
                       (let [consumed (get task-cursor task-key #{})]
                         (reduce (fn [best index]
                                   (if (contains? consumed index)
                                     best
                                     (let [candidate {:task-key task-key
                                                      :index index
                                                      :entry entry
                                                      :sort-key (pending-sort-key
                                                                 task-key
                                                                 index)}]
                                       (if (earlier-pending? candidate best)
                                         candidate
                                         best))))
                                 best
                                 indexes)))
                     nil
                     (net/network-dict-entry n facts/task-index-key))]
    (when best
      (let [{:keys [prop-ids prop-id]} (:entry best)]
        {:task-key (:task-key best)
         :index (:index best)
         :prop-ids (vec (or prop-ids #{prop-id}))}))))

(defn indexed-prop-ids
  [n]
  (vec (net/network-dict-entry n facts/frame-prop-index-key)))

(defn- cell-state
  [n id]
  (let [entry (net/network-env-lookup n id)]
    (cond
      (cell/cell? entry)
      [:cell
       (cell/cell-content entry)
       (cell/cell-strongest entry)]

      :else
      [:entry entry])))

(defn- prop-observed-ids
  [n prop-id prop-io-cache]
  (let [cached-ids (get @prop-io-cache prop-id)
        node (get (net/net-graph n) prop-id)
        entry (get (net/net-env n) prop-id)]
    (cond
      cached-ids
      cached-ids

      (and node (prop/prop? entry))
      (let [observed-ids (cond
                           (= :inputs (:observe entry))
                           (graph/node-input-ids node)

                           :else
                           (distinct
                            (concat (graph/node-input-ids node)
                                    (graph/node-output-ids node))))
            ids (vec (sort-by pr-str observed-ids))]
        (swap! prop-io-cache assoc prop-id ids)
        ids)

      (and (nil? node) (nil? entry))
      nil

      (and (nil? node) (prop/prop? entry))
      (throw (ex-info "indexed GUR propagator is missing its graph node"
                      {:prop-id prop-id
                       :prop-name (prop/prop-name entry)}))

      :else
      (throw (ex-info "indexed GUR task does not name a propagator"
                      {:prop-id prop-id
                       :entry entry
                       :node node})))))

(defn- prop-observed-state
  [n prop-id prop-io-cache]
  (when-let [ids (prop-observed-ids n prop-id prop-io-cache)]
    (mapv (fn [id] [id (cell-state n id)]) ids)))

(defn run-props-with-state-cache
  [n prop-ids prop-state-cache prop-io-cache]
  (loop [tasks (tq/enqueue-all tq/empty-queue prop-ids)
         current n]
    (if (tq/queue-empty? tasks)
      current
      (let [[prop-id remaining] (tq/pop-task tasks)
            observed (prop-observed-state current prop-id prop-io-cache)]
        (instr/observe-prop-run! {:event :considered
                                  :prop-id prop-id
                                  :net current})
        (cond
          (nil? observed)
          (recur remaining current)

          (= observed (get @prop-state-cache prop-id))
          (do
            (instr/observe-prop-run! {:event :skipped
                                      :prop-id prop-id
                                      :net current})
            (recur remaining current))

          :else
          (let [before-env-count (count (net/net-env current))
                before-dict-count (count (net/net-dict-or-empty current))
                before-prop-ids (network-prop-ids current)
                started (System/nanoTime)
                [next-tasks evaluated] (core/eval-propagator prop-id
                                                             remaining
                                                             current)
                emitted-prop-ids (set/difference (network-prop-ids evaluated)
                                                 before-prop-ids)
                next-net (facts/index-frame-props evaluated emitted-prop-ids)
                elapsed-ns (- (System/nanoTime) started)
                after-env-count (count (net/net-env next-net))
                after-dict-count (count (net/net-dict-or-empty next-net))
                observed* (prop-observed-state next-net prop-id prop-io-cache)]
            (instr/observe-prop-run! {:event :ran
                                      :prop-id prop-id
                                      :net current
                                      :elapsed-ns elapsed-ns
                                      :env-delta (- after-env-count before-env-count)
                                      :dict-delta (- after-dict-count before-dict-count)
                                      :changed? (not= next-net current)})
            (when observed*
              (swap! prop-state-cache assoc prop-id observed*))
            (recur next-tasks next-net)))))))
