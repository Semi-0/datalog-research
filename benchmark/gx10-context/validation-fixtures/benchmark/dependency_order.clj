(ns benchmark.dependency-order)
(defn dependency-order [nodes edges]
  (let [rank (zipmap nodes (range))
        edges (set edges)]
    (doseq [[a b] edges]
      (when-not (and (contains? rank a) (contains? rank b))
        (throw (ex-info "Unknown endpoint" {:type :unknown-node}))))
    (let [following (reduce (fn [m [a b]] (update m a (fnil conj []) b)) {} edges)
          degrees (reduce (fn [m [_ b]] (update m b inc)) (zipmap nodes (repeat 0)) edges)
          ready (into (sorted-set) (keep (fn [node] (when (zero? (get degrees node)) (get rank node))) nodes))]
      (loop [degrees degrees ready ready result []]
        (if (empty? ready)
          (if (= (count result) (count nodes)) result (throw (ex-info "Cycle" {:type :cycle})))
          (let [index (first ready) node (nth nodes index)
                [next-degrees next-ready]
                (reduce (fn [[ds rs] child]
                          (let [ds (update ds child dec)]
                            [ds (if (zero? (get ds child)) (conj rs (get rank child)) rs)]))
                        [degrees (disj ready index)] (get following node))]
            (recur next-degrees next-ready (conj result node))))))))
