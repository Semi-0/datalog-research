(ns propagators.network-facts.export
  "Lossless in-process EAV projection. Opaque values are never evaluated."
  (:require [clojure.spec.alpha :as s]
            [propagators.cells.cell :as cell]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(s/def ::fact (s/tuple any? keyword? any?))
(s/def ::facts (s/coll-of ::fact :kind set?))

(def root [:net/root])

(def attributes
  {:net #{:net/graph-node :net/env-entry :net/dictionary-entry
          :net/relationship-node :net/graph-meta :net/env-meta
          :net/dict-meta :net/relationship-meta :record/type :record/meta
          :record/field}
   :graph #{:graph/input-meta :graph/output-meta :graph/present :graph/input :graph/output :record/type
            :record/meta :record/field}
   :relationship #{:graph/input-meta :graph/output-meta :relationship/present :relationship/parent
                   :relationship/child :record/type :record/meta :record/field}
   :env #{:entry/kind :entry/value :cell/name :cell/content :cell/strongest
          :propagator/name :propagator/activate :propagator/recipe
          :record/type :record/meta :record/field}
   :dict #{:dictionary/key :dictionary/value}
   :field #{:field/key :field/value}
   :recipe #{:recipe/kind :recipe/factory :recipe/input-port
             :recipe/output-port :recipe/parameter}
   :port #{:port/position :port/cell}
   :parameter #{:parameter/key :parameter/value :parameter/id?}})

(def plural-attributes
  #{:net/graph-node :net/env-entry :net/dictionary-entry :net/relationship-node
    :graph/input :graph/output :relationship/parent :relationship/child
    :record/field :recipe/input-port :recipe/output-port :recipe/parameter})


(def preserved-attributes
  #{:cell/content :cell/strongest :cell/name :entry/value :record/type
    :record/meta :record/field :field/key :field/value :dictionary/key
    :dictionary/value :net/graph-meta :net/env-meta :net/dict-meta :net/relationship-meta})


(defn fail [reason data]
  (throw (ex-info (name reason) (assoc data :reason reason))))

(defn index-facts [facts]
  (reduce (fn [index [entity attribute value]]
            (update-in index [entity attribute] (fnil conj #{}) value))
          {} facts))

(defn values-at [index entity attribute]
  (get-in index [entity attribute] #{}))

(defn one [index entity attribute]
  (let [values (values-at index entity attribute)]
    (if (= 1 (count values))
      (first values)
      (fail :cardinality {:entity entity :attribute attribute :values values}))))

(defn optional [index entity attribute default]
  (if (contains? (get index entity) attribute)
    (one index entity attribute)
    default))

(defn inspectable-value?
  "Finite transparent collections and scalar values have auditable ID references."
  [value]
  (and (or (nil? (meta value)) (inspectable-value? (meta value)))
       (cond
         (or (nil? value) (ids/node-id? value) (boolean? value) (number? value)
             (keyword? value) (symbol? value) (string? value) (char? value)
             (instance? java.util.UUID value) (instance? java.time.Instant value)) true
         (map? value) (every? inspectable-value? (concat (keys value) (vals value)))
         (or (set? value) (vector? value) (list? value)) (every? inspectable-value? value)
         :else false)))

(defn ids-in
  "Conservative finite-value reference inspection; never rewrite opaque values."
  [value]
  (let [references
        (cond
          (ids/node-id? value) #{value}
          (map? value) (reduce into #{} (map ids-in (concat (keys value) (vals value))))
          (set? value) (reduce into #{} (map ids-in value))
          (sequential? value) (reduce into #{} (map ids-in value))
          :else #{})]
    (if-let [metadata (meta value)]
      (into references (ids-in metadata))
      references)))

(defn normalize-id-map [id-map]
  (if (map? id-map) nil (fail :invalid-id-map {:id-map id-map}))
  (into {} (map (fn [[from _]]
                 (loop [current from visited #{}]
                   (if (contains? visited current)
                     (fail :alias-cycle {:id from :visited visited})
                     (if (contains? id-map current)
                       (recur (get id-map current) (conj visited current))
                       [from current]))))) id-map))

(defn- record-type [value]
  (cond
    (instance? propagators.network.Net value) :net
    (instance? propagators.graph.Node value) :node
    (instance? propagators.cells.cell.Cell value) :cell
    (instance? propagators.propagator.Propagator value) :propagator
    (record? value) :opaque
    (map? value) :map
    :else :opaque))

(defn- record-facts [entity value known-fields]
  (into #{[entity :record/type (record-type value)]
          [entity :record/meta (meta value)]}
        (mapcat (fn [[key field-value]]
                  (let [field [:field entity key]]
                    [[entity :record/field field]
                     [field :field/key key]
                     [field :field/value field-value]])))
        (apply dissoc value known-fields)))

(defn- graph-facts [kind id node]
  (if (graph/node? node) nil (fail :invalid-graph-node {:kind kind :id id}))
  (let [entity [kind id]
        [member present input output]
        (case kind
          :graph [:net/graph-node :graph/present :graph/input :graph/output]
          :relationship [:net/relationship-node :relationship/present
                         :relationship/parent :relationship/child]
          (fail :unknown-graph-kind {:kind kind}))]
    (into (conj (record-facts entity node [:inputs :outputs])
                [root member entity] [entity present true]
                [entity :graph/input-meta (meta (:inputs node))]
                [entity :graph/output-meta (meta (:outputs node))])
          (concat (map (fn [id] [entity input [kind id]]) (:inputs node))
                  (map (fn [id] [entity output [kind id]]) (:outputs node))))))

(defn- entry-facts [id entry]
  (let [entity [:env id]
        type (record-type entry)
        kind (cond
               (= :opaque type) :opaque
               (cell/cell? entry) :cell
               (prop/prop? entry) :propagator
               :else :opaque)
        fields (case kind
                 :cell {:name :cell/name :content :cell/content
                        :strongest :cell/strongest}
                 :propagator {:name :propagator/name
                              :activate :propagator/activate}
                 :opaque {})
        base #{[root :net/env-entry entity] [entity :entry/kind kind]}]
    (if (= :opaque kind)
      (conj base [entity :entry/value entry])
      (into (into base (record-facts entity entry (keys fields)))
            (keep (fn [[key attribute]]
                    (if (contains? entry key)
                      [entity attribute (get entry key)]
                      nil)))
            fields))))

(defn net->facts
  "All four root fields, record shape, metadata and extra fields as triples.
  Functions, nested Nets, and contents remain the original in-memory values."
  [network]
  (if (net/net? network)
    (into (record-facts root network [:graph :env :dict :relationship])
          (concat
           (map (fn [field]
                  [root (keyword "net" (str (name field) "-meta"))
                   (meta (get network field))])
                [:graph :env :dict :relationship])
           (mapcat (fn [[id node]] (graph-facts :graph id node)) (:graph network))
           (mapcat (fn [[id entry]] (entry-facts id entry)) (:env network))
           (mapcat (fn [[key value]]
                     (let [entity [:dict key]]
                       [[root :net/dictionary-entry entity]
                        [entity :dictionary/key key]
                        [entity :dictionary/value value]]))
                   (:dict network))
           (mapcat (fn [[key node]] (graph-facts :relationship key node))
                   (:relationship network))))
    (fail :invalid-net {:network network})))
