(ns examples.lain.rule-fixedpoint.fixtures
  "Bounded source graphs and independent BFS oracle, never runtime answer producers."
  (:require [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn source-specs [predicate tuples]
  (into {} (map-indexed (fn [i tuple] [(keyword (str (name predicate) "-" i))
                                      {:predicate predicate :tuple tuple}]) tuples)))

(defn closure [nodes edges]
  (let [adjacency (reduce (fn [m [a b]] (update m a (fnil conj #{}) b)) {} edges)]
    (into #{}
          (mapcat (fn [start]
                    (loop [pending (seq (get adjacency start)) seen #{}]
                      (if (empty? pending)
                        (map #(vector start %) seen)
                        (let [node (first pending)]
                          (if (contains? seen node)
                            (recur (next pending) seen)
                            (recur (concat (next pending) (get adjacency node)) (conj seen node))))))))
          nodes)))

(defn graph-case [id nodes edges]
  {:id id :catalog :reach :domain (vec nodes) :sources (source-specs :edge edges)
   :expected {:reach (closure nodes edges)} :edges (set edges)})

(defn chain [n]
  (graph-case (keyword (str "chain-" n)) (range n) (mapv vector (range (dec n)) (range 1 n))))

(defn diamond [stages]
  (let [edges (mapcat (fn [i]
                        (let [start (* 3 i) end (+ start 3)]
                          [[start (inc start)] [start (+ start 2)] [(inc start) end] [(+ start 2) end]]))
                      (range stages))]
    (graph-case (keyword (str "diamond-" stages)) (range (inc (* 3 stages))) (vec edges))))

(defn dataflow []
  (let [cells (vec (repeatedly 9 ids/new-node-id))
        topology (nb/install-cells cells)]
    ;; Build real installed propagators, then read their cell-to-cell dataflow.
    (let [topology (reduce (fn [network [input output]]
                             (second (((prop/primitive-propagator ::actual-flow identity) input output) network)))
                           topology (map vector cells (rest cells)))
          edges (into #{} (mapcat (fn [[_ p]]
                                   (for [a (:inputs p) b (:outputs p)] [a b])))
                      (filter (fn [[id _]] (prop/prop? (get (net/net-env topology) id))) (net/net-graph topology)))
          reach (closure cells edges)
          taint (conj (into #{} (filter #(= (first cells) (first %))) reach) [(first cells) (first cells)])]
      {:id :installed-dataflow-9 :catalog :dataflow :domain cells :edges edges
       :sources (assoc (source-specs :flow edges) :taint-root {:predicate :taint :tuple [(first cells) (first cells)]})
       :expected {:reach reach :taint taint} :installed-topology topology})))

(defn cases []
  [(chain 4) (chain 8) (chain 12) (diamond 1) (diamond 2)
   (graph-case :cycle-4 (range 4) [[0 1] [1 2] [2 3] [3 0]])
   (graph-case :disconnected-8 (range 8) [[0 1] [1 2] [4 5] [5 6]])
   (dataflow)])
