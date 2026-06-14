(ns propagators.gur
  "General unbounded recursion frame runner built on evaluator IO."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object.core :as compound-core]
            [propagators.datastructures.compound-object.network-slot :as network-slot]
            [propagators.io :as io]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.reality :as reality]
            [propagators.runtime :as runtime]))

(def prop-ids-key :gur/prop-ids)
(def input-routes-key :gur/input-routes)
(def output-routes-key :gur/output-routes)
(def frame-scope-key :gur/frame)

(defn frame-scope
  "Stable lexical scope for a network-valued GUR frame cell."
  [frame-net-id]
  [frame-scope-key frame-net-id])

(defn- input-route
  [[io-id parent-id child-id]]
  {:io-id io-id :parent-id parent-id :child-id child-id})

(defn- output-route
  [[io-id parent-id]]
  [io-id parent-id])

(declare externalize-value*)

(defn- frame-cell-value
  [frame-net id]
  (let [entry (get (net/net-env frame-net) id)]
    (when (cell/cell? entry)
      (let [v (cell/cell-strongest entry)]
        (when-not (value/unusable? v)
          v)))))

(defn- externalized-accessor-slot-value
  [frame-net seen accessor-net slot-key]
  (some (fn [parent-id]
          (when-let [v (frame-cell-value frame-net parent-id)]
            (externalize-value* frame-net seen v)))
        (sort-by pr-str
                 (network-slot/accessor-parent-ids accessor-net slot-key))))

(defn- externalize-accessor-source-slots
  [frame-net seen value-net]
  (if-not (network-slot/accessor-network? value-net)
    value-net
    (let [slot-values (into {}
                            (keep (fn [slot-key]
                                    (when-let [v
                                               (externalized-accessor-slot-value
                                                frame-net
                                                seen
                                                value-net
                                                slot-key)]
                                      [slot-key v])))
                            (network-slot/accessor-slot-keys value-net))]
      (if (empty? slot-values)
        value-net
        (net/update-net-dict-entry value-net
                                   network-slot/source-slots-key
                                   #(merge (or % {}) slot-values))))))

(defn- externalize-network-value
  [frame-net seen value-net]
  (network-slot/externalize-accessor-value frame-net value-net))

(defn- externalize-value*
  [frame-net seen v]
  (if-not (net/network? v)
    v
    (let [token (System/identityHashCode v)]
      (if (contains? seen token)
        v
        (externalize-network-value frame-net (conj seen token) v)))))

(defn- externalize-value
  [frame-net v]
  (externalize-value* frame-net #{} v))

(defn- p:frame-out
  "Publish a frame output, preserving child-local accessor slot values."
  [io-id cell-id watch-ids]
  (fn [network]
    (let [[prop-id n]
          ((prop/construct-propagator
            (fn [_inputs _outputs n]
              (let [v (net/network-cell-strongest n cell-id)]
                [(io/io-delivery :append-outbox
                                 (io/io-record
                                  io-id
                                  cell-id
                                  (message/message cell-id
                                                   (externalize-value n v))))]))
            (vec (distinct (cons cell-id watch-ids)))
            [])
           network)]
      [prop-id
       (net/assoc-net-dict-entry n [:reality/out-cell io-id] cell-id)])))

(defn- output-watch-ids
  [network cell-id]
  (->> (get (net/network-dict-entry network compound-core/slot-declarations-key)
            cell-id)
       vals
       (mapcat keys)
       (sort-by pr-str)
       vec))

(defn- prop-ids
  [frame-net]
  (vec (or (net/network-dict-entry frame-net prop-ids-key) [])))

(defn- inject-input
  [child-net parent-net {:keys [io-id parent-id child-id]}]
  (let [v (net/network-cell-strongest parent-net parent-id)]
    (if (value/unusable? v)
      child-net
      (reality/inject-input child-net io-id child-id v))))

(defn- translate-outbox
  [frame-net output-map records]
  (keep (fn [{:keys [id message]}]
          (cond
            (contains? output-map id)
            (message/message (get output-map id)
                             (externalize-value frame-net
                                                (message/message-value message)))

            (= :escaped id)
            message

            :else
            nil))
        records))

(defn run-frame
  "Run one frame network value through declared reality IO routes."
  [frame-net parent-net input-routes output-routes]
  (let [inputs (mapv input-route input-routes)
        outputs (into {} (map output-route output-routes))
        child0 (reduce #(inject-input %1 parent-net %2) frame-net inputs)
        child1 (runtime/continue (io/enqueue-props child0 (prop-ids child0)))
        [records child2] (io/drain-outbox child1)]
    {:messages (vec (translate-outbox child2 outputs records))
     :frame-net (io/stored-lexical-env child2)}))

(defn- compatible-frame-update?
  [parent-net frame-net-id frame-net]
  (not (value/contradiction?
        (merge/cell-merge (net/network-cell-content parent-net frame-net-id)
                          frame-net
                          parent-net))))

(defn p:run-frame*
  "Run a network-valued frame cell and write its updated frame value back.

  `input-routes` are `[io-id parent-cell-id child-cell-id]`.
  `output-routes` are `[io-id parent-cell-id]`.
  "
  [scope frame-net-id input-routes output-routes]
  (let [inputs (mapv input-route input-routes)
        parent-inputs (mapv :parent-id inputs)
        parent-outputs (mapv second output-routes)]
    (prop/construct-propagator
     (fn [_input-ids _output-ids parent-net]
       (let [frame-value (net/network-cell-strongest parent-net frame-net-id)]
         (cond
           (value/nothing? frame-value)
           []

           (not (net/network? frame-value))
           [(message/message frame-net-id value/contradiction)]

           :else
           (let [{:keys [messages frame-net]}
                 (run-frame frame-value parent-net input-routes output-routes)]
             (cond-> (into [(io/io-delivery :assoc-lexical-env-if-absent
                                            [scope frame-net])]
                           messages)
               (compatible-frame-update? parent-net frame-net-id frame-net)
               (conj (message/message frame-net-id frame-net)))))))
     (into [frame-net-id] parent-inputs)
     (into [frame-net-id] parent-outputs))))

(defn p:run-frame
  "Run a network-valued frame cell and expose the updated frame as a lexical env."
  ([frame-net-id input-routes output-routes]
   (p:run-frame* (frame-scope frame-net-id)
                 frame-net-id
                 input-routes
                 output-routes))
  ([scope frame-net-id input-routes output-routes]
   (p:run-frame* scope frame-net-id input-routes output-routes)))

(defn install-boundary
  "Install reality IO ports into a frame network and record their prop ids."
  [network {:keys [inputs outputs]}]
  (let [{n1 :net input-props :prop-ids}
        (reduce
         (fn [{:keys [net prop-ids]} [io-id child-id]]
           (let [[prop-id n] ((reality/p:reality-in io-id child-id)
                              (nb/ensure-cell net child-id))]
             {:net n
              :prop-ids (conj prop-ids prop-id)}))
         {:net network :prop-ids []}
         inputs)
        {n2 :net output-props :prop-ids}
        (reduce
         (fn [{:keys [net prop-ids]} [io-id child-id]]
           (let [watch-ids (output-watch-ids net child-id)
                 [prop-id n] ((p:frame-out io-id child-id watch-ids)
                              (nb/ensure-cell net child-id))]
             {:net n
              :prop-ids (conj prop-ids prop-id)}))
         {:net n1 :prop-ids []}
         outputs)
        all-props (into input-props output-props)]
    {:net (-> n2
              (net/update-net-dict-entry prop-ids-key
                                         #(into (vec (or % [])) all-props))
              (net/assoc-net-dict-entry input-routes-key (vec inputs))
              (net/assoc-net-dict-entry output-routes-key (vec outputs)))
     :prop-ids all-props}))
