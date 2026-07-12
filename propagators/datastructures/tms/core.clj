(ns propagators.datastructures.tms.core
  "Small TMS projection built on reducer-cell slot evidence.

  This is not a kernel TMS. Claims and premise states are monotone reducer slots;
  the strongest reducer projection computes the currently active view."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def reducer-id :tms/default)
(def claim-kind :claim)
(def premise-state-kind :premise-state)
(def support-kind :support)
(def distributed-kind :distributed)
(def distributed-projection-kind :distributed-projection)
(def distributed-proposition :tms/value)

(defn- stable-node-id
  [& seed]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str seed) StandardCharsets/UTF_8))))

(defn claim
  [claim-id proposition v supports]
  (obj/compound-object
   {:tms/kind claim-kind
    :tms/claim-id claim-id
    :tms/proposition proposition
    :tms/value v
    :tms/supports (obj/compound-object (vec supports))}))

(defn support
  [premise source kind]
  (obj/compound-object
   {:tms/kind support-kind
    :support/premise premise
    :support/source source
    :support/kind kind}))

(defn claim?
  [x]
  (= claim-kind (obj/slot-value x :tms/kind)))

(defn support?
  [x]
  (= support-kind (obj/slot-value x :tms/kind)))

(defn claim-id [x] (obj/slot-value x :tms/claim-id))
(defn proposition [x] (obj/slot-value x :tms/proposition))
(defn claim-value [x] (obj/slot-value x :tms/value))
(defn support-premise [x] (obj/slot-value x :support/premise))
(defn support-source [x] (obj/slot-value x :support/source))
(defn support-kind-value [x] (obj/slot-value x :support/kind))

(defn- compound-seq-values
  [x]
  (let [count-value (obj/slot-value x :count)]
    (if (integer? count-value)
      (mapv #(obj/slot-value x %) (range count-value))
      (mapv #(obj/slot-value x %)
            (sort-by pr-str (remove #{:count} (obj/public-slot-keys x)))))))

(defn support-objects
  [x]
  (filterv support? (compound-seq-values (obj/slot-value x :tms/supports))))

(defn supports
  [x]
  (set (map support-premise (support-objects x))))

(defn premise-state
  [premise epoch active?]
  (obj/compound-object
   {:tms/kind premise-state-kind
    :tms/premise premise
    :tms/epoch epoch
    :tms/active? (boolean active?)}))

(defn premise-state?
  [x]
  (= premise-state-kind (obj/slot-value x :tms/kind)))

(defn premise [x] (obj/slot-value x :tms/premise))
(defn epoch [x] (obj/slot-value x :tms/epoch))
(defn active? [x] (obj/slot-value x :tms/active?))

(defn claim-slot-key
  [claim-id]
  [:tms/claim claim-id])

(defn premise-slot-key
  [premise epoch]
  [:tms/premise premise epoch])

(defn latest-premise-slot-key
  [premise]
  [:tms/latest-premise premise])

(defn- latest-premise-slot-key?
  [k]
  (and (vector? k)
       (= :tms/latest-premise (first k))))

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

(defn- latest-premise-slots
  [slots]
  (->> (or slots {})
       (keep (fn [[k v]]
               (when (and (vector? k)
                          (= :tms/latest-premise (first k))
                          (premise-state? v))
                 [(second k) v])))
       (into {})))

(defn- without-latest-premise-slots
  [slots]
  (into {}
        (remove (fn [[k _v]]
                  (latest-premise-slot-key? k)))
        (or slots {})))

(defn- current-premise-states
  [slots]
  (latest-premise-slots slots))

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
        latest-premises (current-premise-states slots)
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

(defn support-data
  [x]
  {:support/premise (support-premise x)
   :support/source (support-source x)
   :support/kind (support-kind-value x)})

(defn claim-data
  [x]
  {:tms/claim-id (claim-id x)
   :tms/proposition (proposition x)
   :tms/value (claim-value x)
   :tms/supports (set (map support-data (support-objects x)))})

(defn premise-state-data
  [x]
  {:tms/premise (premise x)
   :tms/epoch (epoch x)
   :tms/active? (active? x)})

(defn distributed-content
  [slots]
  {:tms/kind distributed-kind
   :tms/slots (or slots {})})

(defn distributed-content?
  [x]
  (and (map? x) (= distributed-kind (:tms/kind x))))

(defn distributed-projection
  [v slots]
  {:tms/kind distributed-projection-kind
   :tms/value v
   :tms/slots (or slots {})})

(defn distributed-projection?
  [x]
  (and (map? x) (= distributed-projection-kind (:tms/kind x))))

(defn distributed-value?
  [x]
  (or (distributed-content? x)
      (distributed-projection? x)))

(defn distributed-slots
  [x]
  (cond
    (distributed-content? x) (:tms/slots x)
    (distributed-projection? x) (:tms/slots x)
    :else {}))

(defn distributed-base-value
  [x]
  (if (distributed-projection? x)
    (:tms/value x)
    x))

(defn- fact-data
  [x]
  (cond
    (claim? x) [:claim (claim-data x)]
    (premise-state? x) [:premise-state (premise-state-data x)]
    :else [:value x]))

(defn- fact-equal?
  [a b]
  (= (fact-data a) (fact-data b)))

(defn- merge-slot-maps
  [content update]
  (reduce-kv
   (fn [slots k v]
     (if (contains? slots k)
       (if (fact-equal? (get slots k) v)
         slots
         (reduced value/contradiction))
       (assoc slots k v)))
   (or content {})
   (or update {})))

(declare slots-provenance)

(defn merge-slots
  [content update]
  (let [merged (merge-slot-maps (without-latest-premise-slots content)
                                (without-latest-premise-slots update))]
    (if (value/contradiction? merged)
      (value/contradiction-with-provenance
       (set/union (slots-provenance content)
                  (slots-provenance update)))
      (let [latest (latest-premise-states
                    (filter premise-state? (vals merged)))]
        (reduce-kv
         (fn [slots p state]
           (assoc slots (latest-premise-slot-key p) state))
         merged
         latest)))))

(defn- fact-slot
  [fact]
  (cond
    (claim? fact) [(claim-slot-key (claim-id fact)) fact]
    (premise-state? fact) [(premise-slot-key (premise fact) (epoch fact)) fact]
    :else nil))

(defn- slots-for
  [facts]
  (into {} (keep fact-slot facts)))

(defn distributed-input-update
  [claim-id v premise epoch source]
  (distributed-content
   (merge-slots {}
                (slots-for
                 [(premise-state premise epoch true)
                  (claim claim-id
                         distributed-proposition
                         v
                         [(support premise source :distributed-input)])]))))

(defn distributed-premise-update
  [premise epoch active?]
  (distributed-content
   (merge-slots {}
                (slots-for [(premise-state premise epoch active?)]))))

(defn distributed-forward-update
  [x]
  (when (seq (distributed-slots x))
    (distributed-content (distributed-slots x))))

(defn merge-distributed-content
  [content update]
  (let [content-slots (if (value/nothing? content)
                        {}
                        (distributed-slots content))
        merged (merge-slots content-slots (distributed-slots update))]
    (if (value/contradiction? merged)
      merged
      (distributed-content merged))))

(defn active-claims-for
  [view proposition-id]
  (filterv #(= proposition-id (proposition %))
           (vals (active-claims view))))

(defn- claims-provenance
  [claims]
  (set
   (concat
    (map (fn [c] [:tms/claim (claim-id c)]) claims)
    (mapcat (fn [c]
              (map (fn [p] [:tms/premise p]) (supports c)))
            claims))))

(defn- slots-provenance
  [slots]
  (let [facts (vals (or slots {}))]
    (set/union
     (claims-provenance (filter claim? facts))
     (set (map (fn [state] [:tms/premise (premise state)])
               (filter premise-state? facts))))))

(defn distributed-supports
  [x]
  (let [claims (active-claims-for (tms-view (distributed-slots x))
                                  distributed-proposition)]
    (set (mapcat support-objects claims))))

(defn distributed-premise-slots
  [x]
  (into {}
        (filter (fn [[_ v]] (premise-state? v))
                (distributed-slots x))))

(defn- merge-slot-updates
  [slot-maps]
  (reduce
   (fn [slots update]
     (let [merged (merge-slots slots update)]
       (if (value/contradiction? merged)
         (reduced merged)
         merged)))
   {}
   slot-maps))

(defn distributed-state-update
  [xs]
  (let [slots (merge-slot-updates (map distributed-premise-slots xs))]
    (cond
      (value/contradiction? slots) value/contradiction
      (seq slots)
      (distributed-content slots))))

(defn distributed-result-update
  [claim-id result xs]
  (let [inputs (filter distributed-value? xs)
        states (merge-slot-updates (map distributed-premise-slots inputs))
        supports (apply set/union #{} (map distributed-supports inputs))]
    (cond
      (value/contradiction? states) states
      (or (seq states) (seq supports))
      (distributed-content
       (merge-slots {}
                    (cond-> states
                      (and (not (value/unusable? result))
                           (seq supports))
                      (assoc (claim-slot-key claim-id)
                             (claim claim-id
                                    distributed-proposition
                                    result
                                    supports))))))))

(defn strongest-distributed-value
  [content]
  (let [slots (distributed-slots content)
        view (tms-view slots)
        v (proposition-value view distributed-proposition)]
    (cond
      (value/contradiction? v)
      (value/contradiction-with-provenance
       (claims-provenance
        (active-claims-for view distributed-proposition)))

      (value/unusable? v) v
      :else (distributed-projection v slots))))

(def merge-net
  (let [content-id (stable-node-id ::merge :content)
        update-id (stable-node-id ::merge :update)
        out-id (stable-node-id ::merge :out)
        prop-id (stable-node-id ::merge :project)
        n0 (-> net/empty-net
               (nb/install-cell content-id)
               (nb/install-cell update-id)
               (nb/install-cell out-id))
        [_ n1] ((prop/construct-propagator
                 prop-id
                 (fn [_inputs _outputs network]
                   (let [content (net/network-cell-strongest network content-id)
                         update (net/network-cell-strongest network update-id)
                         content* (if (value/nothing? content) {} content)
                         update* (if (value/nothing? update) {} update)]
                     [(message out-id (merge-slots content* update*))]))
                 [content-id update-id]
                 [out-id])
                n0)]
    (-> n1
        (net/assoc-net-dict-entry :content content-id)
        (net/assoc-net-dict-entry :update update-id)
        (net/assoc-net-dict-entry :out out-id))))

(def strongest-net
  (let [slots-id (stable-node-id ::strongest :slots)
        out-id (stable-node-id ::strongest :out)
        dependence-id (stable-node-id ::strongest :dependence)
        epoch-id (stable-node-id ::strongest :epoch)
        prop-id (stable-node-id ::strongest :project)
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

;; Legacy centralized reducer-cell TMS compatibility. Compiler-2 now uses
;; distributed premise/content updates by default; these remain for tests and
;; callers that still want one explicit reducer storage cell.
(defn tms-cell
  ([] (tms-cell reducer-id))
  ([id] (reducer/reducer-cell id merge-net strongest-net {})))

(defn claim-update
  ([c] (claim-update reducer-id c))
  ([id c]
   (reducer/reducer-slot-update id
                                merge-net
                                strongest-net
                                (claim-slot-key (claim-id c))
                                c)))

(defn premise-update
  ([premise epoch active?]
   (premise-update reducer-id premise epoch active?))
  ([id premise epoch active?]
   (reducer/reducer-slot-update id
                                merge-net
                                strongest-net
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

(defn p:tms-premise-source
  "Emit a premise-state where the premise identity is supplied by a cell."
  [tms-id premise-id epoch active-id tms-cell-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [premise (net/network-cell-strongest network premise-id)
           active (net/network-cell-strongest network active-id)]
       (cond
         (or (value/contradiction? premise)
             (value/contradiction? active))
         [(message tms-cell-id value/contradiction)]

         (or (value/nothing? premise)
             (value/nothing? active))
         []

         :else
         [(message tms-cell-id
                   (premise-update tms-id premise epoch active))])))
   [premise-id active-id]
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
