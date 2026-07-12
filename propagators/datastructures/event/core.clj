(ns propagators.datastructures.event.core
  "Discrete source-aware event partial information."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.network :as net]))

(def fact-kind :event/fact)
(def content-kind :event/content)
(def projection-kind :event/projection)
(def protocol-id :pi/event)

(def protocol-dict-key (obj/internal-metadata-key :pi :direct-protocol-id))
(def protocol-id-key (obj/internal-metadata-key :pi :protocol-id))
(def kind-key (obj/internal-metadata-key :event :kind))
(def input-id-key (obj/internal-metadata-key :event :input-id))
(def source-key (obj/internal-metadata-key :event :source))
(def timestamp-key (obj/internal-metadata-key :event :timestamp))
(def value-key (obj/internal-metadata-key :event :value))
(def source-state-key (obj/internal-metadata-key :event :source-state))
(def evidence-key (obj/internal-metadata-key :event :evidence))
(def projection-facts-key (obj/internal-metadata-key :event :projection-facts))
(def fact-slots-key (obj/internal-metadata-key :event :fact-slots))
(def latest-state-key (obj/internal-metadata-key :event :latest-state))

(def active-state :active)
(def retracted-state :retracted)

(defn fact-key
  [input-id source timestamp]
  [input-id source timestamp])

(defn event-fact
  [{:keys [input-id source timestamp value source-state evidence]
    :or {source-state active-state}}]
  (net/assoc-net-dict-entry
   (obj/compound-object
    (cond-> {:slot timestamp
             :value value
             protocol-id-key protocol-id
             kind-key fact-kind
             input-id-key input-id
             source-key source
             timestamp-key timestamp
             value-key value
             source-state-key source-state}
      evidence (assoc evidence-key evidence)))
   protocol-dict-key
   protocol-id))

(defn active-event
  [input-id source timestamp value]
  (event-fact {:input-id input-id
               :source source
               :timestamp timestamp
               :value value
               :source-state active-state}))

(defn retraction-event
  [input-id source timestamp]
  (event-fact {:input-id input-id
               :source source
               :timestamp timestamp
               :source-state retracted-state}))

(defn event-fact?
  [v]
  (= fact-kind (obj/slot-value v kind-key)))

(defn event-protocol-value?
  [v]
  (= protocol-id
     (if (net/network? v)
       (or (net/network-dict-entry v protocol-dict-key)
           (obj/slot-value v protocol-id-key))
       (obj/slot-value v protocol-id-key))))

(defn protocol-id-of
  [v]
  (when (event-protocol-value? v)
    protocol-id))

(defn input-id [fact] (obj/slot-value fact input-id-key))
(defn source [fact] (obj/slot-value fact source-key))
(defn timestamp [fact] (obj/slot-value fact timestamp-key))
(defn event-value [fact] (obj/slot-value fact value-key))
(defn source-state [fact] (obj/slot-value fact source-state-key))
(defn explicit-evidence [fact] (obj/slot-value fact evidence-key))

(defn active? [fact] (= active-state (source-state fact)))
(defn retracted? [fact] (= retracted-state (source-state fact)))

(defn event-content?
  [v]
  (= content-kind (obj/slot-value v kind-key)))

(defn event-projection?
  [v]
  (= projection-kind (obj/slot-value v kind-key)))

(defn projection-facts
  [v]
  (obj/slot-value v projection-facts-key))

(declare time-rank)

(defn- source-identity-key
  [fact]
  [(input-id fact) (source fact)])

(defn- newer-fact
  [a b]
  (if (pos? (compare (time-rank (timestamp b))
                     (time-rank (timestamp a))))
    b
    a))

(defn- index-latest-state
  [facts]
  (reduce (fn [latest fact]
            (update latest (source-identity-key fact)
                    (fn [current]
                      (if current
                        (newer-fact current fact)
                        fact))))
          {}
          facts))

(defn- fact-slots
  [content]
  (cond
    (event-content? content)
    (or (obj/slot-value content fact-slots-key)
        (into {} (map (fn [fact]
                        [(fact-key (input-id fact)
                                   (source fact)
                                   (timestamp fact))
                         fact]))
              (mapv #(obj/slot-value content %)
                    (obj/public-slot-keys content))))

    (event-fact? content)
    {(fact-key (input-id content)
               (source content)
               (timestamp content))
     content}

    :else nil))

(defn- latest-state
  [content]
  (cond
    (event-content? content)
    (or (obj/slot-value content latest-state-key)
        (index-latest-state (vals (fact-slots content))))

    (event-fact? content)
    {(source-identity-key content) content}

    :else nil))

(defn- content-object
  [slots latest]
  (net/assoc-net-dict-entry
   (obj/compound-object
    (assoc slots
           protocol-id-key protocol-id
           kind-key content-kind
           fact-slots-key slots
           latest-state-key latest))
   protocol-dict-key
   protocol-id))

(defn content-value
  [facts]
  (let [slots (into {} (map (fn [fact]
                              [(fact-key (input-id fact)
                                         (source fact)
                                         (timestamp fact))
                               fact]))
                    facts)]
    (content-object slots (index-latest-state (vals slots)))))

(defn- content-facts
  [content]
  (cond
    (value/nothing? content) []
    (event-fact? content) [content]
    (event-content? content)
    (vals (fact-slots content))
    (event-projection? content)
    (or (projection-facts content) [])
	    :else value/contradiction))

(defn facts
  [content]
  (let [facts* (content-facts content)]
    (if (value/contradiction? facts*) [] facts*)))

(defn- same-fact?
  [a b]
  (and (= (input-id a) (input-id b))
       (= (source a) (source b))
       (= (timestamp a) (timestamp b))
       (= (event-value a) (event-value b))
       (= (source-state a) (source-state b))))

(defn- merge-fact
  [slots fact]
  (let [k (fact-key (input-id fact) (source fact) (timestamp fact))]
    (cond
      (not (event-fact? fact)) value/contradiction
      (contains? slots k) (if (same-fact? (get slots k) fact)
                            slots
                            value/contradiction)
      :else (assoc slots k fact))))

(defn merge-content
  [content update]
  (let [existing (if (value/nothing? content)
                   []
                   (content-facts content))
        incoming (content-facts update)]
    (cond
      (or (value/contradiction? existing)
          (value/contradiction? incoming)) value/contradiction
      (empty? incoming) content
      :else
      (let [existing-slots (or (fact-slots content)
                               (into {} (map (fn [fact]
                                               [(fact-key (input-id fact)
                                                          (source fact)
                                                          (timestamp fact))
                                                fact]))
                                     existing))
            existing-latest (or (latest-state content)
                                (index-latest-state existing))
            merged (reduce
                    (fn [slots fact]
                      (if (value/contradiction? slots)
                        slots
                        (merge-fact slots fact)))
                    existing-slots
                    incoming)
            latest (when-not (value/contradiction? merged)
                     (reduce (fn [acc fact]
                               (clojure.core/update
                                acc
                                (source-identity-key fact)
                                (fn [current]
                                  (if current
                                    (newer-fact current fact)
                                    fact))))
                             existing-latest
                             incoming))]
        (if (value/contradiction? merged)
          value/contradiction
          (content-object merged latest))))))

(defn- evidence-timestamp?
  [x]
  (and (map? x)
       (contains? x :input-id)
       (contains? x :source)
       (contains? x :timestamp)))

(defn- evidence-identity-rank
  [e]
  [(pr-str (:input-id e))
   (pr-str (:source e))])

(defn- evidence-entry-rank
  [e]
  (conj (evidence-identity-rank e)
        (time-rank (:timestamp e))))

(defn- newer-evidence-entry
  [a b]
  (if (pos? (compare (time-rank (:timestamp b))
                     (time-rank (:timestamp a))))
    b
    a))

(defn- canonical-evidence-set
  [xs]
  (set
   (vals
    (reduce (fn [latest e]
              (update latest
                      [(:input-id e) (:source e)]
                      (fn [current]
                        (if current
                          (newer-evidence-entry current e)
                          e))))
            {}
            xs))))

(defn- evidence-set-rank
  [xs]
  ["evidence-set"
   (mapv evidence-entry-rank
         (sort-by evidence-identity-rank
                  (canonical-evidence-set xs)))])

(defn- map-time-rank
  [m]
  (if (evidence-timestamp? m)
    ["evidence" (evidence-entry-rank m)]
    ["map"
     (mapv (fn [[k v]]
             [(pr-str k) (time-rank v)])
           (sort-by (comp pr-str key) m))]))

(defn- collection-time-rank
  [tag xs]
  [tag (mapv time-rank (sort-by pr-str xs))])

(defn- time-rank
  [t]
  (cond
    (number? t) ["scalar" t]
    (inst? t) ["scalar" (.getTime ^java.util.Date t)]
    (and (set? t) (every? evidence-timestamp? t)) (evidence-set-rank t)
    (map? t) (map-time-rank t)
    (set? t) (collection-time-rank "set" t)
    (sequential? t) (collection-time-rank "seq" t)
    :else ["value" (pr-str t)]))

(defn latest-facts-by-source-state
  [content]
  (or (latest-state content)
      (let [grouped (group-by (juxt input-id source) (facts content))]
        (into {}
              (map (fn [[k source-facts]]
                     [k (last (sort-by (comp time-rank timestamp)
                                       source-facts))]))
              grouped))))

(defn latest-facts-by-source
  [content]
  (into {}
        (keep (fn [[k latest]]
                (when (active? latest)
                  [k latest])))
        (latest-facts-by-source-state content)))

(defn latest-facts
  [content]
  (vals (latest-facts-by-source-state content)))

(defn active-facts
  [content]
  (vals (latest-facts-by-source content)))

(defn active-values
  [content]
  (into {}
        (map (fn [fact]
               [[(input-id fact) (source fact)] (event-value fact)]))
        (active-facts content)))

(defn strongest-value
  [content]
  (let [active (active-facts content)]
    (if (empty? active)
      value/nothing
      (let [timestamp-counts (frequencies (map timestamp active))]
        (net/assoc-net-dict-entry
         (obj/compound-object
          (assoc (into {}
                      (map (fn [fact]
	                             [(if (= 1 (get timestamp-counts
	                                             (timestamp fact)))
	                                (timestamp fact)
	                                (fact-key (input-id fact)
	                                          (source fact)
	                                          (timestamp fact)))
	                              (event-value fact)]))
	                      active)
                  protocol-id-key protocol-id
	                kind-key projection-kind
                  projection-facts-key active))
         protocol-dict-key
         protocol-id)))))

(defn fact-evidence
  [fact]
  (or (explicit-evidence fact)
      #{{:input-id (input-id fact)
         :source (source fact)
         :timestamp (timestamp fact)}}))

(defn same-source-compatible?
  [facts]
  (let [by-source (group-by :source (mapcat fact-evidence facts))]
    (every? (fn [[_ xs]]
              (= 1 (count (set (map :timestamp xs)))))
            by-source)))

(defn compatible?
  [contents]
  (same-source-compatible? (mapcat active-facts contents)))

(defn combined-evidence
  [contents]
  (set (mapcat fact-evidence (mapcat active-facts contents))))

(defn evidence
  [content]
  (set (mapcat fact-evidence (facts content))))

(defn- event-bearing?
  [v]
  (or (event-content? v)
      (event-fact? v)
      (event-projection? v)))

(defn- cartesian-product
  [colls]
  (reduce (fn [tuples xs]
            (for [tuple tuples
                  x xs]
              (conj tuple x)))
          [[]]
          colls))

(defn- lift-arg
  [content base-value]
  (if (event-bearing? content)
    (let [active (active-facts content)]
      {:event? true
       :choices (mapv (fn [fact]
                        {:value (event-value fact)
                         :fact fact})
                      active)
       :retraction-facts (if (seq active)
                           active
                           (latest-facts content))})
    {:event? false
     :choices (if (value/unusable? base-value)
                []
                [{:value base-value}])
     :retraction-facts []}))

(defn- tuple-compatible?
  [tuple]
  (same-source-compatible? (keep :fact tuple)))

(defn- tuple-evidence
  [tuple]
  (canonical-evidence-set (mapcat fact-evidence (keep :fact tuple))))

(defn- derived-source
  [claim-id evidence]
  [:event/derived
   claim-id
   (set (map (juxt :input-id :source) evidence))])

(defn- derived-timestamp
  [evidence]
  (canonical-evidence-set evidence))

(defn- derived-active-event
  [claim-id evidence result]
  (let [evidence (canonical-evidence-set evidence)]
    (event-fact {:input-id claim-id
                 :source (derived-source claim-id evidence)
                 :timestamp (derived-timestamp evidence)
                 :value result
                 :evidence evidence})))

(defn- derived-retraction-event
  [claim-id evidence]
  (let [evidence (canonical-evidence-set evidence)]
    (event-fact {:input-id claim-id
                 :source (derived-source claim-id evidence)
                 :timestamp (derived-timestamp evidence)
                 :source-state retracted-state
                 :evidence evidence})))

(defn lift
  "Lift a scalar function over active event facts.

  `arg-contents` are used to find event facts; `arg-bases` are the already
  unwrapped scalar values for non-event inputs."
  [claim-id f arg-contents arg-bases]
  (let [args (mapv lift-arg arg-contents arg-bases)
        event? (some :event? args)]
    (when event?
      (let [tuples (cartesian-product (map :choices args))
            compatible-tuples (filter tuple-compatible? tuples)
            active (mapv (fn [tuple]
                           (let [evidence (tuple-evidence tuple)]
                             (derived-active-event claim-id
                                                   evidence
                                                   (apply f (map :value tuple)))))
                         compatible-tuples)]
        (cond
          (seq active)
          (content-value active)

          (some (comp seq :retraction-facts) args)
          (let [evidence (canonical-evidence-set
                          (mapcat fact-evidence
                                  (mapcat :retraction-facts args)))]
            (derived-retraction-event claim-id evidence))

          :else value/nothing)))))
