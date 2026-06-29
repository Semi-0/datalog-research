(ns propagators.explain.facts
  "EDN fact extraction for propagator networks and retained recursive frames."
  (:require [clojure.string :as str]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.gur.recursive :as recursive]))

(defn- sorted-by-pr-str
  [xs]
  (sort-by pr-str xs))

(defn- fact-key
  [part & xs]
  (keyword (name part)
           (-> (str/join "-" (map pr-str xs))
               (str/replace #"[^A-Za-z0-9_-]+" "-")
               (str/replace #"(^-+|-+$)" ""))))

(defn- node-kind
  [entry]
  (cond
    (cell/cell? entry) :cell
    (prop/prop? entry) :propagator
    :else :unknown))

(defn topology-facts
  "Convert a network graph into deterministic node and edge facts."
  [n]
  (let [g (net/net-graph n)
        env (net/net-env n)
        node-ids (vec (sorted-by-pr-str (keys g)))
        node-facts (map-indexed
                    (fn [idx id]
                      {:fact/id (keyword "topology" (format "node-%04d" idx))
                       :fact/type :node
                       :node/id id
                       :node/kind (node-kind (get env id))})
                    node-ids)
        edge-tuples (for [[from-id node] (sort-by (comp pr-str first) g)
                          to-id (sorted-by-pr-str (graph/node-output-ids node))]
                      [from-id to-id])
        edge-facts (map-indexed
                    (fn [idx [from-id to-id]]
                      (let [from-kind (node-kind (get env from-id))
                            to-kind (node-kind (get env to-id))]
                        {:fact/id (keyword "topology" (format "edge-%04d" idx))
                         :fact/type :edge
                         :from from-id
                         :to to-id
                         :role (cond
                                 (and (= :cell from-kind)
                                      (= :propagator to-kind)) :input
                                 (and (= :propagator from-kind)
                                      (= :cell to-kind)) :output
                                 :else :link)}))
                    edge-tuples)]
    (vec (concat node-facts edge-facts))))

(defn- frame-slot-value
  [frame-net frame slot]
  (when-let [id (net/network-dict-entry frame-net
                                        (recursive/frame-dict-key frame slot))]
    (net/network-cell-strongest frame-net id)))

(defn- truthy-slot?
  [frame-net frame slot]
  (= true (frame-slot-value frame-net frame slot)))

(defn- frame-statuses
  [frame-net frame]
  (->> [:expanded :done :contradiction]
       (filterv #(truthy-slot? frame-net frame [:status %]))))

(defn- primary-status
  [statuses]
  (cond
    (some #{:contradiction} statuses) :contradiction
    (some #{:done} statuses) :done
    (some #{:expanded} statuses) :expanded
    :else :unknown))

(defn- frame-children
  [frame-net frame]
  (->> (range)
       (map (fn [idx] [idx (frame-slot-value frame-net frame [:child idx])]))
       (take-while (fn [[_ child]] (some? child)))
       (mapv second)))

(defn- frame-fact
  [frame-net frame]
  (let [statuses (frame-statuses frame-net frame)
        input (frame-slot-value frame-net frame :input)
        output (frame-slot-value frame-net frame :output)
        children (frame-children frame-net frame)
        combine (frame-slot-value frame-net frame :combine)]
    (cond-> {:fact/id (fact-key :frame frame)
             :fact/type :recursive-frame
             :frame frame
             :status (primary-status statuses)
             :statuses statuses}
      (some? input) (assoc :input input)
      (some? output) (assoc :output output)
      (seq children) (assoc :children children)
      (some? combine) (assoc :combine combine))))

(defn recursive-frame-facts
  "Extract normalized recursive frame facts from an accumulator/closure frame net."
  [frame-net]
  (if (or (nil? frame-net)
          (value/unusable? frame-net)
          (not (net/network? frame-net)))
    []
    (->> (recursive/frame-index frame-net)
         sorted-by-pr-str
         (mapv #(frame-fact frame-net %)))))

(defn- child-edge-facts
  [frame-facts]
  (vec
   (for [{:keys [frame children]} frame-facts
         child children]
     {:fact/id (fact-key :frame-edge frame :to child)
      :fact/type :recursive-child
      :from frame
      :to child})))

(defn final-result-fact
  [op input value]
  {:fact/id (fact-key :result [op input])
   :fact/type :final-result
   :op op
   :input input
   :value value})

(defn- fact-type
  [fact]
  (:fact/type fact))

(defn- recursive-frame-fact?
  [fact]
  (= :recursive-frame (fact-type fact)))

(defn- recursive-child-fact?
  [fact]
  (= :recursive-child (fact-type fact)))

(defn- final-result-fact?
  [fact]
  (= :final-result (fact-type fact)))

(defn- frame->fact-id
  [frame-facts]
  (into {}
        (map (fn [{:keys [frame fact/id]}]
               [frame id]))
        frame-facts))

(defn- child-id
  [frame-id-by-frame child]
  (or (get frame-id-by-frame child)
      child))

(defn root-results-view
  [facts]
  {:view/kind :root-results
   :rows (->> facts
              (filter final-result-fact?)
              (sort-by :fact/id)
              (mapv (fn [{:keys [fact/id op input value]}]
                      {:id id
                       :op op
                       :input input
                       :value value})))})

(defn frame-table-view
  [facts]
  (let [frames (filterv recursive-frame-fact? facts)
        frame-id-by-frame (frame->fact-id frames)]
    {:view/kind :frame-table
     :rows (->> frames
                (sort-by pr-str)
                (sort-by #(get % :input -1) >)
                (mapv (fn [{:keys [fact/id frame input children output status statuses combine]}]
                        (cond-> {:id id
                                 :frame frame
                                 :input input
                                 :children (mapv #(child-id frame-id-by-frame %) children)
                                 :output output
                                 :status status
                                 :statuses statuses}
                          (some? combine) (assoc :combine combine))))) }))

(defn edge-list-view
  [facts]
  (let [frames (filterv recursive-frame-fact? facts)
        frame-id-by-frame (frame->fact-id frames)]
    {:view/kind :edge-list
     :rows (->> facts
                (filter recursive-child-fact?)
                (sort-by :fact/id)
                (mapv (fn [{:keys [fact/id from to]}]
                        {:id id
                         :from (child-id frame-id-by-frame from)
                         :to (child-id frame-id-by-frame to)})))}))

(defn leaf-nodes-view
  [facts]
  {:view/kind :leaf-nodes
   :rows (->> facts
              (filter recursive-frame-fact?)
              (filter #(empty? (:children %)))
              (sort-by :fact/id)
              (mapv (fn [{:keys [fact/id frame input output status statuses]}]
                      {:id id
                       :frame frame
                       :input input
                       :output output
                       :status status
                       :statuses statuses})))})

(defn- child-fact-ids
  [frame-row]
  (vec (:children frame-row)))

(defn expansion-chain-view
  [facts]
  (let [table (:rows (frame-table-view facts))
        row-by-id (into {} (map (juxt :id identity) table))
        roots (->> table
                   (remove #(empty? (child-fact-ids %)))
                   (sort-by :input >)
                   (take 1)
                   (mapv :id))
        walk (fn walk [id path]
               (let [row (get row-by-id id)
                     children (child-fact-ids row)]
                 (if (seq children)
                   (mapcat #(walk % (conj path %)) children)
                   [path])))]
    {:view/kind :expansion-chains
     :roots roots
     :chains (vec (mapcat #(walk % [%]) roots))}))

(defn bottom-up-values-view
  [facts]
  (let [frames (filterv recursive-frame-fact? facts)
        frame-id-by-frame (frame->fact-id frames)]
    {:view/kind :bottom-up-values
     :rows (->> frames
                (sort-by #(get % :input -1))
                (mapv (fn [{:keys [fact/id input output children combine]}]
                        (cond-> {:id id
                                 :input input
                                 :output output}
                          (seq children)
                          (assoc :children (mapv #(child-id frame-id-by-frame %) children))

                          (some? combine)
                          (assoc :combine combine)))))}))

(defmulti derive-view
  "Derive a question-oriented view from generic fact maps."
  (fn [view-kind _facts _opts] view-kind))

(defmethod derive-view :root-results
  [_ facts _opts]
  (root-results-view facts))

(defmethod derive-view :frame-table
  [_ facts _opts]
  (frame-table-view facts))

(defmethod derive-view :edge-list
  [_ facts _opts]
  (edge-list-view facts))

(defmethod derive-view :leaf-nodes
  [_ facts _opts]
  (leaf-nodes-view facts))

(defmethod derive-view :expansion-chains
  [_ facts _opts]
  (expansion-chain-view facts))

(defmethod derive-view :bottom-up-values
  [_ facts _opts]
  (bottom-up-values-view facts))

(defmethod derive-view :default
  [view-kind _facts _opts]
  {:view/kind view-kind
   :rows []})

(def default-view-kinds
  [:root-results
   :frame-table
   :edge-list
   :leaf-nodes
   :expansion-chains
   :bottom-up-values])

(defn derived-views
  ([facts]
   (derived-views facts {}))
  ([facts {:keys [view-kinds]
           :or {view-kinds default-view-kinds}
           :as opts}]
   (mapv #(derive-view % facts opts) view-kinds)))

(defn fib-evidence
  "Select deterministic high-signal evidence for explaining Fibonacci recursion."
  [{:keys [n value frame-net topology-net include-topology?]}]
  (let [frames (recursive-frame-facts frame-net)
        sorted-frames (->> frames
                           (sort-by pr-str)
                           (sort-by #(get % :input -1) >))
        frame-edges (child-edge-facts frames)
        result [(final-result-fact :fib n value)]
        topology (if include-topology?
                   (topology-facts topology-net)
                   [])]
    (vec (concat result
                 sorted-frames
                 frame-edges
                 topology))))

(defn- render-edn-lines
  [xs]
  (binding [*print-length* 120
            *print-level* 10]
    (with-out-str
      (doseq [x xs]
        (prn x)))))

(defn prompt
  [{:keys [question evidence views raw-facts?]
    :or {raw-facts? true}}]
  (let [views (or views (derived-views evidence))
        rendered-views (render-edn-lines views)
        rendered-facts (render-edn-lines evidence)]
    (str "You are explaining a propagator execution from evidence facts.\n"
         "Use the derived views as already-parsed evidence.\n"
         "Use raw facts only to audit citations.\n"
         "Every concrete conclusion must cite fact ids.\n"
         "If the facts do not prove something, say that.\n\n"
         "Question: " question "\n\n"
         "Derived views:\n"
         rendered-views
         (when raw-facts?
           (str "\nRaw facts:\n"
                rendered-facts)))))
