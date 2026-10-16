(ns examples.lain.live-environment.retirement
  "Conservative same-ID retirement; never rewrites surviving activations."
  (:require [clojure.set :as set]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]))

(defn references? [removed value]
  (boolean (some #(and (ids/node-id? %) (contains? removed %))
                 (tree-seq #(and (coll? %) (not (ids/node-id? %))) seq value))))

(defn- trim-dictionary [removed value]
  (cond
    (not (references? removed value)) value
    (ids/node-id? value) (when-not (contains? removed value) value)
    (record? value)
    (throw (ex-info "Retirement needs a dictionary record reconstruction contract"
                    {:type (type value)}))
    (map? value)
    (reduce-kv (fn [result key child]
                 (if (references? removed key)
                   result
                   (let [trimmed (trim-dictionary removed child)]
                     (if (and (some? child) (nil? trimmed))
                       result
                       (assoc result key trimmed)))))
               (empty value) value)
    (set? value) (into #{} (remove #(references? removed %)) value)
    (sequential? value) (into [] (remove #(references? removed %)) value)
    :else value))

(defn- validate-receipts! [network ledger removed]
  (doseq [id removed]
    (when-let [activation (get-in ledger [:nodes id :activation])]
      (when-not (identical? activation (prop/prop-f (get (:env network) id)))
        (throw (ex-info "Installed activation no longer matches retirement receipt" {:id id})))))
  (doseq [[id entry] (:env network)
          :when (and (prop/prop? entry) (not (contains? removed id)))]
    (let [node (get (:graph network) id)
          missing (set/intersection removed (into (:inputs node) (:outputs node)))]
      (when (seq missing)
        (throw (ex-info "Surviving propagator depends on retired topology"
                        {:id id :references missing}))))))

(defn- validate-values! [network ledger removed]
  (doseq [[key value] (:dict network)
          :when (and (references? removed [key value])
                     (not (contains? (:dictionary-keys ledger) key)))]
    (throw (ex-info "Unmanaged dictionary entry references retired topology" {:key key})))
  (doseq [[id entry] (:env network)
          :when (and (cell/cell? entry) (not (contains? removed id))
                     (not (value/unusable? (cell/cell-strongest entry)))
                     (references? removed (cell/cell-strongest entry)))]
    (throw (ex-info "Surviving active value references retired topology"
                    {:id id :name (cell/cell-name entry)
                     :value-type (type (cell/cell-strongest entry))}))))

(defn classify-reuse
  "Distinguish unchanged immutable cell definitions from obsolete private nodes.
  Propagators cannot be reused by this rule, even if their IDs match."
  [before after removed]
  (let [recreated (set/intersection removed (set (keys (:env after))))]
    (doseq [id recreated]
      (let [old (get (:env before) id)
            fresh (get (:env after) id)]
        (when-not (and (cell/cell? old) (cell/cell? fresh) (= old fresh))
          (throw (ex-info "Replacement recreated changed retired topology"
                          {:id id})))))
    (let [retired (set/difference removed recreated)]
      ;; No dictionary or usable value may still point to actually retired IDs.
      (validate-values! after {:dictionary-keys #{}} retired)
      {:retired retired :reused recreated})))

(defn- retired-key? [removed [path id]]
  (or (references? removed path)
      (and (= [:outer] path) (contains? removed id))))

(defn retire [network ledger removed]
  (validate-receipts! network ledger removed)
  (validate-values! network ledger removed)
  (let [kept (set/difference (set (keys (:env network))) removed)
        pruned-graph
        (into {} (for [[id node] (:graph network) :when (contains? kept id)]
                   [id (graph/node (set/intersection kept (:inputs node))
                                   (set/intersection kept (:outputs node)))]))
        keys (into #{} (remove #(retired-key? removed %)) (keys (:relationship network)))]
    (net/net pruned-graph (apply dissoc (:env network) removed)
             (trim-dictionary removed (:dict network))
             (relationship/project (:relationship network) keys))))
