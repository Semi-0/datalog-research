(ns propagators.datastructures.behavior
  "Sparse temporal behavior histories and reducer topology.

  A behavior cell's content is a retained sparse history object. Its strongest
  value is the latest value projected from that history, so ordinary operators
  can consume behavior cells as current values while history-aware operators can
  inspect cell content explicitly."
  (:require [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def behavior-kind :behavior/sparse-history)
(def base-layer :base)
(def summary-layer :behavior/summary)
(def summary-reducer-key :behavior/reducer)
(def summary-source-keys-key :behavior/source-keys)
(def summary-source-count-key :behavior/source-count)
(def summary-retained-count-key :behavior/retained-count)
(def summary-history-keys-key :behavior/history-keys)
(def state-events-layer :behavior/events)
(def state-history-layer :behavior/history)

(def kind-key (obj/internal-metadata-key :behavior :kind))
(def source-keys-key (obj/internal-metadata-key :behavior :source-keys))
(def reducer-key (obj/internal-metadata-key :behavior :reducer))

(def reducer-id-key :behavior/reducer)
(def event-slot-prefix :behavior/event)

(def event-history-reducer-id :behavior.reducer/event-history)
(def constant-history-reducer-id :behavior.reducer/constant-history)

(defn point-event
  [t v]
  (obj/compound-object {:at t :value v}))

(defn bounded-interval
  [from to v]
  (obj/compound-object {:from from :to to :value v}))

(defn constant-interval
  [from v]
  (obj/compound-object {:from from :to :infinity :value v}))

(defn event-slot-key
  [tick]
  [event-slot-prefix tick])

(defn- decode-event-slot-key
  [slot-key]
  (cond
    (integer? slot-key) slot-key
    (and (vector? slot-key)
         (= event-slot-prefix (first slot-key))
         (integer? (second slot-key))
         (= 2 (count slot-key))) (second slot-key)
    :else ::invalid-event-slot))

(defn- public-slot-map
  [x]
  (let [collection (obj/compound-object x)]
    (if (value/contradiction? collection)
      value/contradiction
      (into {}
            (map (fn [slot-key]
                   [slot-key (obj/slot-value collection slot-key)]))
            (obj/public-slot-keys collection)))))

(declare public-snapshot)

(defn- public-snapshot
  [x]
  (cond
    (value/unusable? x) x
    :else
    (let [slots (public-slot-map x)]
      (if (value/contradiction? slots)
        x
        (into {}
              (map (fn [[slot-key slot-value]]
                     [slot-key (public-snapshot slot-value)]))
              slots)))))

(defn- history-map
  [history]
  (cond
    (nil? history) {}
    (net/network? history) (public-slot-map history)
    (map? history) (into {} history)
    :else value/contradiction))

(defn behavior-value
  "Build a sparse-history compound object.

  Public slots are temporal records keyed by tick or segment start. Reducer
  identity and folded source evidence are hidden metadata used by the cell
  protocol to decide when a newer retained view supersedes an older one."
  [{:keys [history source-keys reducer]}]
  (let [history* (history-map history)]
    (if (value/contradiction? history*)
      value/contradiction
      (obj/compound-object
       (assoc history*
              kind-key behavior-kind
              source-keys-key (set source-keys)
              reducer-key reducer)))))

(defn history-state
  "Reducer accumulator value with slot-addressable event evidence and history."
  [events history]
  (let [events* (history-map events)
        history* (history-map history)]
    (if (or (value/contradiction? events*)
            (value/contradiction? history*))
      value/contradiction
      (obj/compound-object
       {state-events-layer (obj/compound-object events*)
        state-history-layer (obj/compound-object history*)}))))

(defn state-events
  [state]
  (cond
    (and (map? state)
         (contains? state :events)
         (contains? state :history)) (:events state)
    :else
    (let [events (public-slot-map (obj/slot-value state state-events-layer))]
      (if (value/contradiction? events) {} events))))

(defn state-history
  [state]
  (cond
    (and (map? state)
         (contains? state :events)
         (contains? state :history)) (:history state)
    :else
    (obj/slot-value state state-history-layer)))

(defn state-history-map
  [state]
  (let [history (state-history state)]
    (cond
      (net/network? history)
      (let [history* (public-slot-map history)]
        (if (value/contradiction? history*) {} history*))

      (map? history) history
      :else
      (let [history* (public-slot-map history)]
        (if (value/contradiction? history*) {} history*)))))

(defn history
  "Return the sparse history compound object stored as behavior content."
  [v]
  (obj/compound-object v))

(defn history-records
  [v]
  (let [history-object (history v)]
    (if (value/contradiction? history-object)
      []
      (mapv #(obj/slot-value history-object %)
            (sort (obj/public-slot-keys history-object))))))

(defn source-keys
  [v]
  (let [ks (obj/slot-value v source-keys-key)]
    (if (set? ks) ks #{})))

(defn reducer-id
  [v]
  (obj/slot-value v reducer-key))

(defn base-value
  [v]
  (obj/slot-value v base-layer))

(defn summary
  [v]
  (obj/slot-value v summary-layer))

(defn summary-source-keys
  [v]
  (let [ks (obj/slot-value (summary v) summary-source-keys-key)]
    (if (set? ks) ks #{})))

(defn summary-retained-count
  [v]
  (let [n (obj/slot-value (summary v) summary-retained-count-key)]
    (if (integer? n) n 0)))

(defn behavior-value?
  [v]
  (and (= behavior-kind (obj/slot-value v kind-key))
       (set? (obj/slot-value v source-keys-key))
       (some? (obj/slot-value v reducer-key))
       (not (value/contradiction? (history v)))))

(defn- behavior-candidates?
  [v]
  (and (sequential? v)
       (seq v)
       (every? behavior-value? v)))

(defn behavior-content?
  [v]
  (or (behavior-value? v)
      (behavior-candidates? v)))

(defn- candidates
  [content]
  (cond
    (value/nothing? content) []
    (behavior-value? content) [content]
    (behavior-candidates? content) (vec content)
    :else value/contradiction))

(defn- same-behavior-view?
  [a b]
  (and (= (reducer-id a) (reducer-id b))
       (= (source-keys a) (source-keys b))
       (= (public-snapshot a) (public-snapshot b))))

(defn- source-superset?
  [a b]
  (set/superset? (source-keys a) (source-keys b)))

(defn merge-content
  [content update]
  (let [existing (candidates content)]
    (cond
      (value/contradiction? existing) value/contradiction
      (not (behavior-value? update)) value/contradiction
      (empty? existing) update
      (not (every? #(= (reducer-id update) (reducer-id %)) existing))
      value/contradiction
      (some #(and (= (source-keys update) (source-keys %))
                  (not (same-behavior-view? update %)))
            existing)
      value/contradiction
      (some #(same-behavior-view? update %) existing) content
      (every? #(source-superset? update %) existing) update
      (some #(source-superset? % update) existing) content
      :else value/contradiction)))

(defn- latest-record
  [behavior]
  (let [history-object (history behavior)
        slot-keys (sort (obj/public-slot-keys history-object))]
    (when (seq slot-keys)
      (obj/slot-value history-object (last slot-keys)))))

(defn- record-value
  [record]
  (obj/slot-value record :value))

(defn- behavior-summary-value
  [behavior record]
  (let [history-keys (sort (obj/public-slot-keys (history behavior)))
        source-keys* (source-keys behavior)]
    (obj/compound-object
     {base-layer (record-value record)
      summary-layer
      (obj/compound-object
       {summary-reducer-key (reducer-id behavior)
        summary-source-keys-key source-keys*
        summary-source-count-key (count source-keys*)
        summary-retained-count-key (count history-keys)
        summary-history-keys-key (vec history-keys)})})))

(defn- strongest-behavior-view
  [content]
  (let [existing (candidates content)]
    (cond
      (value/contradiction? existing) value/contradiction
      (empty? existing) value/nothing
      (= 1 (count existing)) (first existing)
      (not= 1 (count (set (map reducer-id existing)))) value/contradiction
      :else
      (let [strongest (filter (fn [candidate]
                                (every? #(source-superset? candidate %)
                                        existing))
                              existing)]
        (if (= 1 (count strongest))
          (first strongest)
          value/contradiction)))))

(defn strongest-value
  "Project behavior content to its latest value.

  The retained sparse history remains in cell content. This projection is what
  lets ordinary operators treat a behavior cell as the current value."
  [content]
  (let [view (strongest-behavior-view content)]
    (cond
      (value/unusable? view) view
      :else (or (some->> view latest-record (behavior-summary-value view))
                value/nothing))))

(defn empty-history-state
  []
  (history-state {} {}))

(defn- sorted-events
  [events]
  (sort-by key events))

(defn- point-history-state
  [events]
  (history-state events
                 (into {}
                       (map (fn [[t v]] [t (point-event t v)]))
                       (sorted-events events))))

(defn- same-segment-value?
  [a b]
  (= (obj/slot-value a :value)
     (obj/slot-value b :value)))

(defn- coalesce-adjacent
  [segments]
  (reduce
   (fn [acc segment]
     (if (and (seq acc)
              (same-segment-value? (peek acc) segment))
       (conj (pop acc)
             (obj/compound-object
              {:from (obj/slot-value (peek acc) :from)
               :to (obj/slot-value segment :to)
               :value (obj/slot-value segment :value)}))
       (conj acc segment)))
   []
   segments))

(defn- constant-history-state
  [events]
  (let [ordered (vec (sorted-events events))
        event-segments (mapv (fn [idx [t v]]
                               (if-let [[next-t] (get ordered (inc idx))]
                                 (bounded-interval t next-t v)
                                 (constant-interval t v)))
                             (range)
                             ordered)
        default-segment (when-let [[first-t] (first ordered)]
                          (when (pos? first-t)
                            (bounded-interval 0 first-t value/nothing)))
        segments (coalesce-adjacent
                  (cond-> []
                    default-segment (conj default-segment)
                    true (into event-segments)))]
    (history-state
     events
     (into {}
           (map (fn [segment]
                  [(obj/slot-value segment :from) segment]))
           segments))))

(defn- window-history-state
  [n events]
  (let [state (point-history-state events)]
    (history-state events
                   (into {}
                         (take-last n (sort-by key (state-history-map state)))))))

(defn- add-event
  [state update build-state]
  (let [{:keys [slot value]} update
        tick (decode-event-slot-key slot)
        events (state-events state)]
    (cond
      (not (integer? tick)) value/contradiction
      (and (contains? events tick)
           (not= (get events tick) value)) value/contradiction
      :else (build-state (assoc events tick value)))))

(defn- reducer-merge-net
  [reducer-id f]
  (let [acc-id (ids/new-node-id)
        update-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (nb/install-cells [acc-id update-id out-id])
        [_ n1] (((prop/primitive-propagator f) acc-id update-id out-id) n0)]
    (-> n1
        (net/assoc-net-dict-entry :acc acc-id)
        (net/assoc-net-dict-entry :update update-id)
        (net/assoc-net-dict-entry :out out-id)
        (net/assoc-net-dict-entry reducer-id-key reducer-id))))

(defn event-history-reducer-net
  []
  (reducer-merge-net
   event-history-reducer-id
   (fn [state update]
     (add-event state update point-history-state))))

(defn constant-history-reducer-net
  []
  (reducer-merge-net
   constant-history-reducer-id
   (fn [state update]
     (add-event state update constant-history-state))))

(defn window-history-reducer-id
  [n]
  [:behavior.reducer/window-history n])

(defn window-history-reducer-net
  [n]
  (when-not (pos-int? n)
    (throw (ex-info "window history reducer requires a positive integer"
                    {:n n})))
  (reducer-merge-net
   (window-history-reducer-id n)
   (fn [state update]
     (add-event state update #(window-history-state n %)))))

(defn p:event
  [tick value-id history-id]
  (when-not (integer? tick)
    (throw (ex-info "behavior event tick must be an integer" {:tick tick})))
  (fn [n]
    ((obj/p:slot (event-slot-key tick) value-id history-id)
     (-> n
         (nb/ensure-cell value-id)
         (nb/ensure-cell history-id)))))

(defn- source-event-keys
  [source]
  (set (map decode-event-slot-key
            (obj/public-slot-keys (obj/compound-object source)))))

(defn- reducer-id-from-merge-net
  [merge-net]
  (when (net/network? merge-net)
    (net/network-dict-entry merge-net reducer-id-key)))

(defn- valid-raw-history?
  [raw]
  (and (not (value/contradiction? raw))
       (map? (state-events raw))
       (not (value/contradiction? (state-history raw)))))

(defn- wrap-behavior-messages
  [source-id merge-net-id raw-id out-id network]
  (let [source (net/network-cell-strongest network source-id)
        merge-net (net/network-cell-strongest network merge-net-id)
        raw (net/network-cell-strongest network raw-id)]
    (cond
      (or (value/unusable? merge-net)
          (value/unusable? raw)) []
      (or (value/contradiction? source)
          (value/contradiction? merge-net)
          (value/contradiction? raw)) [(message out-id value/contradiction)]
      :else
      (let [source-keys* (source-event-keys source)
            folded-keys (set (keys (state-events raw)))
            reducer-id (reducer-id-from-merge-net merge-net)]
        (cond
          (not (every? integer? source-keys*)) [(message out-id value/contradiction)]
          (nil? reducer-id) [(message out-id value/contradiction)]
          (not (valid-raw-history? raw)) [(message out-id value/contradiction)]
          :else
          [(message out-id
                    (behavior-value
                     {:history (state-history raw)
                      :source-keys folded-keys
                      :reducer reducer-id}))])))))

(defn- p:wrap-behavior
  [source-id merge-net-id raw-id out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (wrap-behavior-messages source-id merge-net-id raw-id out-id network))
   [source-id merge-net-id raw-id]
   [out-id]))

(defn p:behavior
  [source-id merge-net-id init-id out-id]
  (fn [n]
    (let [raw-id (ids/new-node-id)
          n0 (nb/ensure-cell n raw-id)
          [reduce-prop n1] ((obj/p:reduce source-id merge-net-id init-id raw-id)
                            n0)
          [wrap-prop n2] ((p:wrap-behavior source-id merge-net-id raw-id out-id)
                          n1)]
      [[reduce-prop wrap-prop] n2])))
