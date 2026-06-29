(ns propagators.datastructures.tms
  "Small TMS projection built on reducer-cell slot evidence.

  This is not a kernel TMS. Claims and premise states are monotone reducer slots;
  the strongest reducer projection computes the currently active view."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def reducer-id :tms/default)

(defn- stable-node-id
  [& seed]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str seed) StandardCharsets/UTF_8))))

(defn claim
  [claim-id proposition v supports]
  {:tms/kind :claim
   :tms/claim-id claim-id
   :tms/proposition proposition
   :tms/value v
   :tms/supports (set supports)})

(defn claim?
  [x]
  (and (map? x)
       (= :claim (:tms/kind x))
       (contains? x :tms/claim-id)
       (contains? x :tms/proposition)
       (contains? x :tms/value)
       (set? (:tms/supports x))))

(defn claim-id [x] (:tms/claim-id x))
(defn proposition [x] (:tms/proposition x))
(defn claim-value [x] (:tms/value x))
(defn supports [x] (:tms/supports x))

(defn premise-state
  [premise epoch active?]
  {:tms/kind :premise-state
   :tms/premise premise
   :tms/epoch epoch
   :tms/active? (boolean active?)})

(defn premise-state?
  [x]
  (and (map? x)
       (= :premise-state (:tms/kind x))
       (contains? x :tms/premise)
       (contains? x :tms/epoch)
       (contains? x :tms/active?)))

(defn premise [x] (:tms/premise x))
(defn epoch [x] (:tms/epoch x))
(defn active? [x] (:tms/active? x))

(defn- epoch-rank
  [e]
  (if (number? e)
    [0 e]
    [1 (pr-str e)]))

(defn- latest-premise-states
  [states]
  (->> states
       (group-by premise)
       (map (fn [[p xs]]
              [p (last (sort-by (comp epoch-rank epoch) xs))]))
       (into {})))

(defn- claim-active?
  [active-premises c]
  (every? active-premises (supports c)))

(defn- proposition-entry
  [claims]
  (let [values (set (map claim-value claims))]
    (if (= 1 (count values))
      {:tms/status :justified
       :tms/value (first values)
       :tms/claims (set (map claim-id claims))}
      {:tms/status :conflict
       :tms/value value/contradiction
       :tms/values values
       :tms/claims (set (map claim-id claims))})))

(defn tms-view
  [slots]
  (let [facts (vals (or slots {}))
        claims (filterv claim? facts)
        latest-premises (latest-premise-states (filter premise-state? facts))
        active-premises (->> latest-premises
                             (filter (comp active? val))
                             (map key)
                             set)
        active-claims (filterv #(claim-active? active-premises %) claims)
        inactive-claims (remove #(claim-active? active-premises %) claims)
        propositions (->> active-claims
                          (group-by proposition)
                          (map (fn [[p xs]]
                                 [p (proposition-entry xs)]))
                          (into {}))]
    {:tms/active-premises active-premises
     :tms/premises latest-premises
     :tms/active-claims (into {} (map (juxt claim-id identity) active-claims))
     :tms/inactive-claims (into {} (map (juxt claim-id identity) inactive-claims))
     :tms/propositions propositions}))

(defn proposition-entry-for
  [view proposition]
  (get-in view [:tms/propositions proposition]))

(defn proposition-value
  [view proposition]
  (if-let [entry (proposition-entry-for view proposition)]
    (:tms/value entry)
    value/nothing))

(defn active-premises [view] (:tms/active-premises view))
(defn propositions [view] (:tms/propositions view))
(defn active-claims [view] (:tms/active-claims view))

(defn- projection-dependence
  [view]
  (set
   (concat
    (map (fn [p] [:tms/premise p]) (active-premises view))
    (map (fn [claim-id] [:tms/claim claim-id]) (keys (active-claims view))))))

(defn- projection-epoch
  [slots]
  [:tms/epoch (hash (pr-str (sort-by (comp pr-str key) (or slots {}))))])

(def reducer-net
  (let [slots-id (stable-node-id ::reducer :slots)
        out-id (stable-node-id ::reducer :out)
        dependence-id (stable-node-id ::reducer :dependence)
        epoch-id (stable-node-id ::reducer :epoch)
        prop-id (stable-node-id ::reducer :project)
        n0 (-> net/empty-net
               (nb/install-cell slots-id)
               (nb/install-cell out-id)
               (nb/install-cell dependence-id)
               (nb/install-cell epoch-id))
        [_ n1] ((prop/construct-propagator
                 prop-id
                 (fn [_inputs _outputs network]
                   (let [slots (net/network-cell-strongest network slots-id)
                         view (tms-view slots)]
                     [(message out-id view)
                      (message dependence-id (projection-dependence view))
                      (message epoch-id (projection-epoch slots))]))
                 [slots-id]
                 [out-id dependence-id epoch-id])
                n0)]
    (-> n1
        (net/assoc-net-dict-entry :slots slots-id)
        (net/assoc-net-dict-entry :out out-id)
        (net/assoc-net-dict-entry :dependence dependence-id)
        (net/assoc-net-dict-entry :epoch epoch-id))))

(defn tms-cell
  ([] (tms-cell reducer-id))
  ([id] (reducer/reducer-cell id reducer-net)))

(defn claim-slot-key
  [claim-id]
  [:tms/claim claim-id])

(defn premise-slot-key
  [premise epoch]
  [:tms/premise premise epoch])

(defn claim-update
  ([c] (claim-update reducer-id c))
  ([id c]
   (reducer/reducer-slot-update id reducer-net (claim-slot-key (claim-id c)) c)))

(defn premise-update
  ([premise epoch active?]
   (premise-update reducer-id premise epoch active?))
  ([id premise epoch active?]
   (reducer/reducer-slot-update id
                                reducer-net
                                (premise-slot-key premise epoch)
                                (premise-state premise epoch active?))))

(defn p:tms-claim
  [tms-id claim-id proposition supports value-id tms-cell-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [v (net/network-cell-strongest network value-id)]
       (cond
         (value/contradiction? v)
         [(message tms-cell-id value/contradiction)]

         (value/nothing? v)
         []

         :else
         [(message tms-cell-id
                   (claim-update tms-id
                                 (claim claim-id proposition v supports)))])))
   [value-id]
   [tms-cell-id]))

(defn p:tms-premise
  [tms-id premise epoch active-id tms-cell-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [v (net/network-cell-strongest network active-id)]
       (cond
         (value/contradiction? v)
         [(message tms-cell-id value/contradiction)]

         (value/nothing? v)
         []

         :else
         [(message tms-cell-id
                   (premise-update tms-id premise epoch v))])))
   [active-id]
   [tms-cell-id]))

(defn p:tms-proposition
  "Project the currently active proposition value to an ordinary output cell.

  This is a raw monotone adapter: if a later TMS epoch makes the proposition
  inactive, the old ordinary output value is not retracted. Consumers that need
  retraction-like behavior should inspect the TMS reducer-cell strongest view."
  [tms-cell-id proposition out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [v (net/network-cell-strongest network tms-cell-id)]
       (cond
         (value/contradiction? v)
         [(message out-id value/contradiction)]

         (value/nothing? v)
         []

         (reducer/reduced-value? v)
         (let [projected (proposition-value (reducer/reduced-result v)
                                            proposition)]
           (if (value/nothing? projected)
             []
             [(message out-id projected)]))

         :else
         [(message out-id value/contradiction)])))
   [tms-cell-id]
   [out-id]))
