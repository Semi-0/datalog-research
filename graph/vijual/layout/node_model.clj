(ns graph.vijual.layout.node-model
  "Validation and pure edit transactions for continuous layout nodes."
  (:require [clojure.set :as set]))

(def default-options
  {:id-fn :id :position-fn :position :size-fn :size
   :with-position (fn [node position] (assoc node :position position))
   :iterations 100 :tolerance 0.0 :gap 10.0 :edge-length 80.0
   :disconnected-weight 0.02 :pinned-ids #{}})

(defn require-valid! [valid? message data]
  (when-not valid? (throw (ex-info message data))))

(defn finite-number? [value]
  (and (number? value) (Double/isFinite (double value))))

(defn- numeric-pair? [value]
  (and (sequential? value) (= 2 (count value))
       (every? finite-number? value)))

(defn validate-budget! [{:keys [iterations tolerance]}]
  (require-valid! (and (integer? iterations) (<= 0 iterations))
                  "Iterations must be a nonnegative integer" {:iterations iterations})
  (require-valid! (and (finite-number? tolerance) (<= 0 tolerance))
                  "Tolerance must be finite and nonnegative" {:tolerance tolerance}))

(defn options [opts]
  (let [opts (merge default-options opts)]
    (validate-budget! opts)
    (doseq [k [:gap :disconnected-weight]]
      (require-valid! (and (finite-number? (get opts k)) (<= 0 (get opts k)))
                      "Option must be finite and nonnegative" {k (get opts k)}))
    (require-valid! (and (finite-number? (:edge-length opts))
                         (<= 0.1 (:edge-length opts)))
                    "Edge length must be finite and at least 0.1" opts)
    (doseq [k [:id-fn :position-fn :size-fn :with-position]]
      (require-valid! (ifn? (get opts k)) "Adapter must be callable" {:adapter k}))
    opts))

(defn normalize [nodes opts]
  (let [items
        (mapv (fn [node]
                (let [id ((:id-fn opts) node)
                      position ((:position-fn opts) node)
                      size ((:size-fn opts) node)]
                  (require-valid! (some? id) "Node needs an id" {:node node})
                  (require-valid! (numeric-pair? position)
                                  "Position needs two finite numbers" {:id id})
                  (require-valid! (and (numeric-pair? size) (every? #(<= 0 %) size))
                                  "Size needs two finite nonnegative numbers" {:id id})
                  {:id id :position (vec position) :size (mapv double size)})) nodes)
        ids (mapv :id items)]
    (require-valid! (= (count ids) (count (set ids))) "Node ids must be unique" {:ids ids})
    items))

(defn validate-edges! [ids edges]
  (let [known (set ids)]
    (doseq [edge edges]
      (require-valid! (and (sequential? edge) (= 2 (count edge))
                           (every? #(contains? known %) edge))
                      "Edge must name two existing nodes" {:edge edge}))))

(defn canonical-edges [ids edges]
  (let [indices (zipmap ids (range))]
    (->> edges
         (remove (fn [[a b]] (= a b)))
         (map (fn [[a b]]
                (if (< (indices a) (indices b)) [a b] [b a])))
         distinct
         (sort-by (fn [[a b]] [(indices a) (indices b)]))
         vec)))

(defn validate-pins! [ids pins]
  (require-valid! (set/subset? (set pins) (set ids))
                  "Pins must name existing nodes" {:pins pins}))

(defn node-deltas [before after id-fn]
  (let [old (into {} (map (juxt id-fn identity) before))
        new (into {} (map (juxt id-fn identity) after))]
    (into (mapv #(vector % -1) (remove #(= % (get new (id-fn %))) before))
          (map #(vector % 1) (remove #(= % (get old (id-fn %))) after)))))

(def ^:private change-keys
  #{:upsert-nodes :remove-node-ids :add-edges :remove-edges :pin-ids :unpin-ids})

(defn- validate-shapes! [changes]
  (require-valid! (map? changes) "Changes must be a map" {:changes changes})
  (doseq [[key value] changes]
    (require-valid! (or (nil? value) (sequential? value) (set? value))
                    "Change entries must be collections" {:key key :value value}))
  (require-valid! (or (nil? (:upsert-nodes changes)) (sequential? (:upsert-nodes changes)))
                  "Node upserts must be an ordered sequence" {:changes changes})
  (doseq [edge (concat (:add-edges changes) (:remove-edges changes))]
    (require-valid! (and (sequential? edge) (= 2 (count edge)))
                    "Edge must contain two endpoints" {:edge edge})))

(defn- validate-changes! [state changes upserts]
  (require-valid! (map? changes) "Changes must be a map" {:changes changes})
  (require-valid! (set/subset? (set (keys changes)) change-keys)
                  "Unknown layout change" {:keys (keys changes)})
  (doseq [[left right] [[(set (map :id upserts)) (set (:remove-node-ids changes))]
                      [(set (:pin-ids changes)) (set (:unpin-ids changes))]
                      [(set (map set (:add-edges changes)))
                       (set (map set (:remove-edges changes)))]]]
    (require-valid! (empty? (set/intersection left right))
                    "Conflicting layout changes" {:changes changes}))
  (let [known (set (map :id (:normalized state)))]
    (require-valid! (set/subset? (set (:remove-node-ids changes)) known)
                    "Cannot remove unknown nodes" {:changes changes})
    (validate-edges! known (:remove-edges changes))))

(defn apply-changes [state changes]
  (validate-shapes! changes)
  (let [opts (:opts state)
        upserts (normalize (vec (:upsert-nodes changes)) opts)
        _ (validate-changes! state changes upserts)
        removed (set (:remove-node-ids changes))
        id-fn (:id-fn opts)
        by-id (into {} (map (juxt id-fn identity) (:upsert-nodes changes)))
        old-ids (set (map :id (:normalized state)))
        survivors (remove #(contains? removed (id-fn %)) (:nodes state))
        nodes (into (mapv #(get by-id (id-fn %) %) survivors)
                    (remove #(contains? old-ids (id-fn %)) (:upsert-nodes changes)))
        normalized (normalize nodes opts)
        ids (mapv :id normalized)
        removed-edges (set (map set (:remove-edges changes)))
        edges (into (vec (remove (fn [[a b :as edge]]
                                  (or (contains? removed a) (contains? removed b)
                                      (contains? removed-edges (set edge)))) (:edges state)))
                    (:add-edges changes))
        pins (set/union (set/difference (:pinned-ids state) removed
                                         (set (:unpin-ids changes)))
                        (set (:pin-ids changes)))]
    (validate-edges! ids edges)
    (validate-pins! ids (concat (:pin-ids changes) (:unpin-ids changes)))
    (validate-pins! ids pins)
    {:nodes nodes :normalized normalized :edges (canonical-edges ids edges)
     :pinned-ids pins}))
