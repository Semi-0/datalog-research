(ns propagators.network-facts.import
  "Validate triples and reconstruct records without running the network."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as compound]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-facts.export :as e]
            [propagators.propagator :as prop]))

(defn members [index attribute]
  (e/values-at index e/root attribute))

(defn- require-members [index attribute tag]
  (doseq [entity (members index attribute)]
    (if (and (= tag (first entity)) (contains? index entity))
      nil
      (e/fail :invalid-membership {:attribute attribute :entity entity}))))

(defn record-fields [index entity]
  (let [fields (map (fn [field]
                      [(e/one index field :field/key)
                       (e/one index field :field/value)])
                    (e/values-at index entity :record/field))]
    (if (= (count fields) (count (set (map first fields))))
      (into {} fields)
      (e/fail :duplicate-field {:entity entity}))))

(defn- validate-record [index entity allowed-types known]
  (let [type (e/one index entity :record/type)
        metadata (e/one index entity :record/meta)
        extra (record-fields index entity)]
    (doseq [field (e/values-at index entity :record/field)]
      (if (= [:field entity (e/one index field :field/key)] field)
        nil
        (e/fail :invalid-field-owner {:entity entity :field field})))
    (if (and (contains? allowed-types type)
             (or (nil? metadata) (map? metadata))
             (empty? (set/intersection known (set (keys extra)))))
      nil
      (e/fail :invalid-record {:entity entity :type type :extra extra}))))

(defn- validate-adjacency [index entities kind input output present]
  (doseq [entity entities]
    (if (and (true? (e/one index entity present))
             (= kind (first entity)))
      nil
      (e/fail :invalid-presence {:entity entity}))
    (validate-record index entity #{:node :map} #{:inputs :outputs})
    (doseq [attribute [:graph/input-meta :graph/output-meta]]
      (let [metadata (e/one index entity attribute)]
        (if (or (nil? metadata) (map? metadata))
          nil
          (e/fail :invalid-metadata {:entity entity :attribute attribute}))))
    (doseq [[attribute reciprocal] [[input output] [output input]]
            neighbor (e/values-at index entity attribute)]
      (if (and (contains? entities neighbor)
               (contains? (e/values-at index neighbor reciprocal) entity))
        nil
        (e/fail :malformed-adjacency {:entity entity :neighbor neighbor
                                      :attribute attribute})))))

(defn ordered-ports [index recipe direction]
  (let [ports (e/values-at index recipe (keyword "recipe" (str (name direction) "-port")))
        _ (doseq [port ports]
            (let [position (e/one index port :port/position)]
              (if (and (integer? position) (<= 0 position)
                       (= [:port (second recipe) direction position] port))
                nil
                (e/fail :invalid-port-order {:recipe recipe :port port :position position}))))
        ordered (sort-by #(e/one index % :port/position) ports)
        positions (mapv #(e/one index % :port/position) ordered)]
    (if (= (vec (range (count ports))) positions)
      (mapv #(e/one index % :port/cell) ordered)
      (e/fail :invalid-port-order {:recipe recipe :positions positions}))))

(defn parameters [index recipe]
  (let [entities (e/values-at index recipe :recipe/parameter)
        pairs (map (fn [entity]
                     [(e/one index entity :parameter/key)
                      (e/one index entity :parameter/value)]) entities)]
    (if (= (count pairs) (count (set (map first pairs))))
      (into {} pairs)
      (e/fail :duplicate-parameter {:recipe recipe}))))

(defn recipe [index entity]
  (if (contains? (get index entity) :propagator/recipe)
    (let [id (e/one index entity :propagator/recipe)]
      {:id id :kind (e/one index id :recipe/kind)
       :factory (e/one index id :recipe/factory)
       :parameters (parameters index id)
       :inputs (ordered-ports index id :input)
       :outputs (ordered-ports index id :output)})
    nil))

(defn- validate-recipe [index entity graph-entities env-entities]
  (if-let [{:keys [id kind factory inputs outputs]} (recipe index entity)]
    (let [node [:graph (second entity)]]
      (if (and (= [:recipe (second entity)] id) (keyword? kind) (fn? factory)
               (= (set inputs) (set (map second (e/values-at index node :graph/input))))
               (= (set outputs) (set (map second (e/values-at index node :graph/output)))))
        nil
        (e/fail :recipe-boundary-mismatch {:entity entity :recipe id}))
      (doseq [port (concat inputs outputs)]
        (if (and (contains? graph-entities [:graph port])
                 (= :cell (e/one index [:env port] :entry/kind)))
          nil
          (e/fail :invalid-port-cell {:recipe id :cell port})))
      (doseq [parameter (e/values-at index id :recipe/parameter)]
        (let [id-valued? (e/one index parameter :parameter/id?)]
          (if (boolean? id-valued?)
            (if id-valued?
              (if (contains? env-entities [:env (e/one index parameter :parameter/value)])
                nil
                (e/fail :missing-parameter-reference {:parameter parameter}))
              nil)
            (e/fail :invalid-parameter-marker {:parameter parameter})))))
    nil))

(defn- validate-entries [index graph-entities env-entities]
  (doseq [entity env-entities]
    (let [allowed (case (e/one index entity :entry/kind)
                    :cell #{:entry/kind :cell/name :cell/content :cell/strongest
                            :record/type :record/meta :record/field}
                    :propagator #{:entry/kind :propagator/name :propagator/activate
                                  :propagator/recipe :record/type :record/meta :record/field}
                    :opaque #{:entry/kind :entry/value}
                    (e/fail :unknown-entry {:entity entity}))]
      (if (set/subset? (set (keys (get index entity))) allowed)
        nil
        (e/fail :invalid-entry-attribute {:entity entity})))
    (case (e/one index entity :entry/kind)
      :cell (do (e/one index entity :cell/content)
                (e/one index entity :cell/strongest)
                (validate-record index entity #{:cell :map} #{:name :content :strongest}))
      :propagator
      (do
        (validate-record index entity #{:propagator :map} #{:name :activate})
        (if (and (ifn? (e/one index entity :propagator/activate))
                 (contains? graph-entities [:graph (second entity)]))
          nil
          (e/fail :invalid-executable {:entity entity}))
        (doseq [neighbor (concat (e/values-at index [:graph (second entity)] :graph/input)
                                (e/values-at index [:graph (second entity)] :graph/output))]
          (if (= :cell (e/one index [:env (second neighbor)] :entry/kind))
            nil
            (e/fail :invalid-executable-boundary {:entity entity :neighbor neighbor})))
        (validate-recipe index entity graph-entities env-entities))
      :opaque (e/one index entity :entry/value)
      (e/fail :unknown-entry {:entity entity}))))

(defn validate-slot-index [index]
  (let [entity [:dict compound/slot-declarations-key]
        declarations (or (e/optional index entity :dictionary/value {}) {})]
    (if (and (map? declarations) (every? map? (vals declarations))
             (every? map? (mapcat vals (vals declarations))))
      nil
      (e/fail :invalid-slot-index {:declarations declarations}))
    (doseq [[owner slots] declarations [key parents] slots [parent declaration] parents]
      (let [prop-id (:prop-id declaration)
            r (recipe index [:env prop-id])]
        (if (and (= :cell (e/one index [:env owner] :entry/kind))
                 (= :cell (e/one index [:env parent] :entry/kind))
                 (= :propagator (e/one index [:env prop-id] :entry/kind))
                 (if r
                   (and (= :slot (:kind r))
                        (= key (get (:parameters r) :slot-key))
                        (= [parent owner] (:inputs r))
                        (= [parent owner] (:outputs r)))
                   (and (= #{[:graph parent] [:graph owner]}
                           (e/values-at index [:graph prop-id] :graph/input))
                        (= #{[:graph parent] [:graph owner]}
                           (e/values-at index [:graph prop-id] :graph/output)))))
          nil
          (e/fail :invalid-slot-index {:owner owner :slot key :parent parent :propagator prop-id}))))))

(defn- referenced-entities [index]
  (set (concat [e/root]
               (mapcat #(members index %) [:net/graph-node :net/env-entry
                                           :net/dictionary-entry :net/relationship-node])
               (mapcat (fn [[_ fields]]
                         (mapcat #(get fields % #{})
                                 [:record/field :propagator/recipe :recipe/input-port
                                  :recipe/output-port :recipe/parameter])) index))))

(defn- validate-index-shape [index]
    (doseq [[entity fields] index]
      (if (and (vector? entity)
               (contains? e/attributes (if (= e/root entity) :net (first entity)))
               (case (first entity)
                 :net/root (= entity e/root)
                 (:graph :env :dict :recipe :relationship) (= 2 (count entity))
                 (:field :parameter) (= 3 (count entity))
                 :port (= 4 (count entity))
                 false))
        nil
        (e/fail :unknown-entity {:entity entity}))
      (doseq [[attribute values] fields]
        (if (and (contains? (get e/attributes (if (= e/root entity) :net (first entity))) attribute)
                 (or (contains? e/plural-attributes attribute) (= 1 (count values))))
          nil
          (e/fail :invalid-attribute {:entity entity :attribute attribute})))))

(defn validate-facts
  "Throw ex-info with :reason on invalid shape, references or executable ports."
  [facts]
  (if (s/valid? ::e/facts facts)
    nil
    (e/fail :invalid-triples {:explain (s/explain-data ::e/facts facts)}))
  (let [index (e/index-facts facts)
        graphs (members index :net/graph-node)
        entries (members index :net/env-entry)]
    (validate-index-shape index)
    (validate-record index e/root #{:net :map} #{:graph :env :dict :relationship})
    (doseq [attribute [:net/graph-meta :net/env-meta :net/dict-meta :net/relationship-meta]]
      (let [metadata (e/one index e/root attribute)]
        (if (or (nil? metadata) (map? metadata))
          nil
          (e/fail :invalid-metadata {:attribute attribute}))))
    (doseq [[attribute tag] [[:net/graph-node :graph] [:net/env-entry :env]
                            [:net/dictionary-entry :dict] [:net/relationship-node :relationship]]]
      (require-members index attribute tag))
    (doseq [entity (concat graphs entries)]
      (if (ids/node-id? (second entity)) nil (e/fail :invalid-node-id {:entity entity})))
    (validate-adjacency index graphs :graph :graph/input :graph/output :graph/present)
    (validate-adjacency index (members index :net/relationship-node)
                        :relationship :relationship/parent :relationship/child :relationship/present)
    (doseq [entity (members index :net/relationship-node)]
      (let [[path id :as key] (second entity)]
        (if (and (vector? key) (= 2 (count key)) (vector? path) (ids/node-id? id))
          nil
          (e/fail :invalid-relationship-key {:entity entity}))))
    (validate-entries index graphs entries)
    (doseq [entity (members index :net/dictionary-entry)]
      (if (= (second entity) (e/one index entity :dictionary/key))
        (e/one index entity :dictionary/value)
        (e/fail :dictionary-key-mismatch {:entity entity})))
    (if (= (set (keys index)) (referenced-entities index))
      {:valid? true :entities (count index) :triples (count facts)}
      (e/fail :orphan-entity {:entities (set/difference (set (keys index)) (referenced-entities index))}))))

(defn restore-record [index entity fields]
  (let [value (merge fields (record-fields index entity))
        restored (case (e/one index entity :record/type)
                   :net (net/map->Net value)
                   :node (graph/map->Node value)
                   :cell (cell/map->Cell value)
                   :propagator (prop/map->Propagator value)
                   :map value
                   (e/fail :unsupported-record {:entity entity}))]
    (with-meta restored (e/one index entity :record/meta))))

(defn- restore-graph [index entities input output]
  (into {} (map (fn [entity]
                 [(second entity)
                  (restore-record index entity
                                  {:inputs (with-meta (set (map second (e/values-at index entity input)))
                                                        (e/one index entity :graph/input-meta))
                                   :outputs (with-meta (set (map second (e/values-at index entity output)))
                                                         (e/one index entity :graph/output-meta))})]))
        entities))

(defn- named-fields [index entity kind fields]
  (let [attribute (keyword (name kind) "name")]
    (if (contains? (get index entity) attribute)
      (assoc fields :name (e/one index entity attribute))
      fields)))

(defn- restore-entry [index entity]
  (case (e/one index entity :entry/kind)
    :opaque (e/one index entity :entry/value)
    :cell (restore-record index entity
                          (named-fields index entity :cell
                                        {:content (e/one index entity :cell/content)
                                         :strongest (e/one index entity :cell/strongest)}))
    :propagator (restore-record index entity
                                (named-fields index entity :propagator
                                              {:activate (e/one index entity :propagator/activate)}))
    (e/fail :unknown-entry {:entity entity})))

(defn- executable-changed? [index source entity id-map]
  (let [node [:graph (second entity)]]
    (or (seq id-map)
        (not= (get index node) (get source node))
        (not= (recipe index entity) (recipe source entity))
        (not (identical? (e/one index entity :propagator/activate)
                        (e/one source entity :propagator/activate))))))

(defn- rebuild-executable [network index entity]
  (if-let [{:keys [factory parameters inputs outputs]} (recipe index entity)]
    (let [id (second entity)
          original (get (:env network) id)
          activate (factory parameters inputs outputs)]
      (if (fn? activate)
        (let [[_ installed] ((prop/construct-propagator id (:name original) activate inputs outputs) network)
              rebuilt (assoc original :activate (get-in installed [:env id :activate]))]
          ;; Constructor supplies the executable; all declared record fields stay exact.
          (assoc-in network [:env id] rebuilt))
        (e/fail :invalid-factory-result {:entity entity})))
    (e/fail :missing-recipe {:entity entity})))

(defn- validate-alias-references [source target removed mapping]
    (doseq [[from to] mapping]
      (if (and (contains? source [:env from]) (contains? source [:env to])
               (= :cell (e/one source [:env from] :entry/kind))
               (= :cell (e/one source [:env to] :entry/kind))
               (contains? target [:env to]) (contains? removed from))
        nil
        (e/fail :missing-alias-destination {:from from :to to}))
      (doseq [id [from to] attribute [:cell/content :cell/strongest]]
        (if (= value/nothing (e/one source [:env id] attribute))
          nil
          (e/fail :populated-contraction {:from from :to to})))))

(defn validate-reconstruction
  "Validate references and evidence before calling any reconstruction factory."
  [facts source-facts id-map]
  (let [mapping (e/normalize-id-map id-map)
        source (e/index-facts source-facts) target (e/index-facts facts)
        removed (set/difference (set (map second (members source :net/env-entry)))
                                (set (map second (members target :net/env-entry))))]
    (validate-alias-references source target removed mapping)
    (doseq [entity (members source :net/env-entry)]
      (if (and (contains? #{:cell :opaque} (e/one source entity :entry/kind))
               (not (contains? mapping (second entity)))
               (not (contains? target entity)))
        (e/fail :unmapped-cell-removal {:entity entity})
        nil))
    (doseq [entity (members source :net/dictionary-entry)]
      (if (contains? target entity) nil (e/fail :opaque-value-changed {:entity entity})))
    (doseq [[entity attribute old] source-facts
            :when (and (contains? e/preserved-attributes attribute)
                       (contains? target entity))]
      (let [expected (if (and (= :dictionary/value attribute) (ids/node-id? old))
                       (get mapping old old) old)]
        (if (and (= [:dict compound/slot-declarations-key] entity)
                 (= :dictionary/value attribute))
          nil
          (if (contains? facts [entity attribute expected])
            nil
            (e/fail :opaque-value-changed {:entity entity :attribute attribute})))))
    (doseq [[entity attribute value] facts :when (seq removed)]
      (if (and (not (contains? #{:propagator/activate :recipe/factory} attribute))
               (not (e/inspectable-value? value)))
        (e/fail :uninspectable-reference {:entity entity :attribute attribute})
        nil)
      (if (seq (set/intersection removed (e/ids-in value)))
        (e/fail :retired-reference {:entity entity :attribute attribute})
        nil))
    (doseq [entity (members target :net/env-entry)]
      (if (= :propagator (e/one target entity :entry/kind))
        (if (and (or (not (contains? source entity))
                     (executable-changed? target source entity mapping))
                 (nil? (recipe target entity)))
          (e/fail :missing-recipe {:entity entity})
          nil)
        nil))
    (let [entity [:dict compound/slot-declarations-key]]
      (if (and (contains? target entity)
               (or (seq mapping) (not= (get source entity) (get target entity))))
        (validate-slot-index target)
        nil))
    mapping))

(defn facts->net
  "Reconstruct a separate Net. Changed executable boundaries require factories.
  Supplying source-facts is mandatory when importing a rewrite. No activation runs."
  ([facts] (facts->net facts {:source-facts facts :id-map {}}))
  ([facts {:keys [source-facts id-map] :or {id-map {}}}]
   (validate-facts facts)
   (if source-facts nil (e/fail :missing-source-facts {}))
   (validate-facts source-facts)
   (validate-reconstruction facts source-facts id-map)
   (let [index (e/index-facts facts)
         source (e/index-facts source-facts)
         graph (restore-graph index (members index :net/graph-node) :graph/input :graph/output)
         env (into {} (map (fn [entity] [(second entity) (restore-entry index entity)]))
                   (members index :net/env-entry))
         dict (into {} (map (fn [entity] [(e/one index entity :dictionary/key)
                                        (e/one index entity :dictionary/value)]))
                    (members index :net/dictionary-entry))
         relationship (restore-graph index (members index :net/relationship-node)
                                     :relationship/parent :relationship/child)
         fields (into {} (map (fn [[key value]]
                                [key (with-meta value (e/one index e/root
                                                           (keyword "net" (str (name key) "-meta"))))]))
                      {:graph graph :env env :dict dict :relationship relationship})
         restored (restore-record index e/root fields)]
     (reduce (fn [network entity]
               (if (= :propagator (e/one index entity :entry/kind))
                 (if (contains? source entity)
                   (if (executable-changed? index source entity id-map)
                     (rebuild-executable network index entity)
                     network)
                   (rebuild-executable network index entity))
                 network))
             restored (members index :net/env-entry)))))
