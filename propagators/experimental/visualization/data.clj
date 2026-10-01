(ns propagators.experimental.visualization.data
  "Read-only source references and cell-backed collection declarations."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.scope-source :as scope]
            [propagators.datastructures.tms.core :as tms]
            [propagators.experimental.visualization.ttms :as ttms]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.relationship-observer :as observer]))

(defn evidence-value [v]
  (let [v (ttms/payload (scope/unwrap v))]
    (tms/distributed-base-value
     (if (tms/distributed-content? v)
       (tms/strongest-distributed-value v)
       v))))

(defn payload [v] (dependency/unwrap (evidence-value v)))

(defn reference
  ([id] (reference [:outer] id []))
  ([path id slots] {:source/path path :source/cell id :source/slots (vec slots)}))

(defn reference? [v]
  (and (map? v) (contains? v :source/path) (contains? v :source/cell)))

(defn slot-value [network object slot]
  (let [object (payload object)]
    (cond
      (value/unusable? object) object
      (obj/accessor-source-slot-present? object slot)
      (obj/accessor-source-slot-value object slot)
      (seq (obj/accessor-parent-ids object slot))
      (let [values (distinct (map #(net/network-cell-strongest network %)
                                  (obj/accessor-parent-ids object slot)))]
        (if (= 1 (count values))
          (first values)
          (throw (ex-info "Source slot has unresolved multiple parents" {:slot slot}))))
      :else value/nothing)))

(defn- read-reference [network ref read-cell]
  (let [{:source/keys [path cell slots]} ref
        owner (observer/network-at-path network path)]
    (if (and owner (contains? (net/net-env owner) cell))
      (reduce #(slot-value owner %1 %2) (read-cell owner cell) slots)
      value/nothing)))

(defn read-source [network ref]
  (read-reference network ref net/network-cell-content))

(defn read-strongest [network ref]
  (read-reference network ref net/network-cell-strongest))

(defn watch-ids [network ref]
  (let [{:source/keys [path cell slots]} ref
        top (if (= [:outer] path) cell (second (second path)))]
    (if (not= [:outer] path)
      ;; Inner IDs belong to the child network, not the outer scheduler.
      ;; Publishing a changed child network updates its outer owner cell.
      [top]
      (loop [v (when (contains? (net/net-env network) cell)
              (net/network-cell-strongest network cell))
           remaining slots
           ids #{top}]
      (if (or (empty? remaining) (value/unusable? v) (nil? v))
        (vec (remove nil? ids))
        (recur (slot-value network v (first remaining))
               (rest remaining)
               (into ids (obj/accessor-parent-ids (payload v) (first remaining)))))))))

(defn supported [claim result sources contents]
  (let [origins (apply set/union (set sources)
                       (map #(dependency/sources (evidence-value %))
                            contents))
        ttms? (some ttms/supported? contents)
        layered (if ttms?
                  (ttms/dependency-datum claim result origins)
                  (dependency/dependency-value result origins))]
    (if ttms?
      (ttms/publication (if (value/unusable? result) result layered) contents)
      (let [update (tms/distributed-result-update claim layered contents)]
        (if (some? update) update layered)))))

(defn state-messages
  "Blocked computation still transports explicit TTMS source state."
  [target contents]
  (if (some ttms/supported? contents)
    [(message/message target (ttms/state-update contents))]
    []))

(defn collection? [v]
  (contains? #{:list :graph} (obj/accessor-source-slot-value (payload v) :collection/type)))

(defn field [v k] (obj/accessor-source-slot-value (payload v) k))

(defn fragment [m] (obj/as-accessor-network m))

(defn entries [v]
  (->> (obj/accessor-slot-keys (payload v))
       (filter #(and (vector? %) (= :element (first %))))
       (map #(field v %))
       (sort-by (juxt :order (comp pr-str :identity)))
       vec))

(defn decision [network entry]
  (let [gates (map #(payload (net/network-cell-strongest network %)) (:gates entry))
        selections
        (map (fn [{:keys [control candidate sources]}]
               (let [control-value (net/network-cell-strongest network control)
                     selected (payload control-value)]
                 (if (value/unusable? selected)
                   selected
                   (or (= selected candidate) (contains? sources selected)))))
             (:selections entry))
        values (concat gates selections)]
    (cond
      (some value/unusable? values) :pending
      (some false? values) :excluded
      (every? true? values) :included
      :else (throw (ex-info "Filter predicate must produce a Boolean" {:values values})))))

(defn entry-inputs [entry]
  (vec (distinct (concat [(:value entry)] (:gates entry) (map :control (:selections entry))))))

(defn resolve-collection
  "Explicit finite observation only; execution traverses list slots incrementally."
  [network collection]
  (let [all (entries collection)
        rows (mapv (fn [entry]
                     (let [v (payload (net/network-cell-strongest network (:value entry)))
                           membership (decision network entry)]
                       (assoc entry
                              :membership (if (and (= :included membership) (value/unusable? v))
                                            :pending membership)
                              :content (net/network-cell-content network (:value entry))
                              :payload v))) all)
        included (filterv #(and (= :included (:membership %))
                                (not (value/unusable? (:payload %)))) rows)
        identities (set (map :identity included))
        graph-ref (field collection :collection/graph-source)
        graph (if (reference? graph-ref) (payload (read-source network graph-ref)) {})]
    {:kind (field collection :collection/type)
     :items (mapv (fn [row]
                    (assoc row :label (get (:nodes graph) (:identity row))
                               :node-kind (get (:node-kinds graph) (:identity row)))) included)
     :candidates rows
     :edges (filterv (fn [[a b]] (and (contains? identities a) (contains? identities b)))
                     (:edges graph))
     :sources (field collection :collection/sources)}))
