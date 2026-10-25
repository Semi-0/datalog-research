(ns propagators.compiler-2.runtime.operators.bounded-history
  "Application-owned incoming-value windows. Recorder state lives in immutable
  reducer cells; published TTMS evidence follows the runtime's retention policy."
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.operator-value :as operator]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.experimental.ttms-publication :as publication]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur.flat :as gur]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.observation :as observation]))

(defrecord Recording [capacity events sequence])

(defn new-recording [capacity]
  (when-not (pos-int? capacity)
    (throw (ex-info "history requires a positive integer capacity" {:capacity capacity})))
  (->Recording capacity [] 0))

(defn recording? [candidate] (instance? Recording candidate))

(defn- checked-recording [recording]
  (when-not (recording? recording)
    (throw (ex-info "Expected a bounded recording" {})))
  recording)

(defn recording-values [recording]
  (mapv :value (:events (checked-recording recording))))

(defn recording-count [recording]
  (count (:events (checked-recording recording))))

(defn recording-sequence [recording]
  (:sequence (checked-recording recording)))

(defn record-value
  "Record a changed current publication in local arrival order. The same payload
  under a new publication stamp is a new observation; reevaluation is not."
  [recording payload stamp]
  (when-not (recording? recording)
    (throw (ex-info "Expected a bounded recording" {})))
  (if (and (seq (:events recording)) (= stamp (:stamp (peek (:events recording)))))
    recording
    (let [sequence (inc (:sequence recording))
          event {:sequence sequence :stamp stamp :value payload}]
      (->Recording (:capacity recording)
                   (vec (take-last (:capacity recording) (conj (:events recording) event)))
                   sequence))))

(defn merge-recordings
  "Top-N union of compatible single-writer observations. Equal sequence numbers
  must identify equal facts; conflicting branches are inspectable errors."
  [left right]
  (when-not (and (recording? left) (recording? right)
                 (= (:capacity left) (:capacity right)))
    (throw (ex-info "Cannot merge incompatible bounded recordings" {})))
  (let [events (reduce (fn [known event]
                         (if-let [previous (get known (:sequence event))]
                           (if (= previous event)
                             known
                             (throw (ex-info "Conflicting bounded history observation"
                                             {:sequence (:sequence event)})))
                           (assoc known (:sequence event) event)))
                       {} (concat (:events left) (:events right)))
        retained (take-last (:capacity left) (sort-by :sequence (vals events)))]
    (->Recording (:capacity left) (vec retained)
                 (max (:sequence left) (:sequence right)))))

(defn- template [input-names output-names calculate]
  (let [names (into (vec input-names) output-names)
        ports (zipmap names (repeatedly (count names) ids/new-node-id))
        initial (reduce-kv net/assoc-net-dict-entry
                           (nb/install-cells (vals ports)) ports)
        [_ installed]
        ((prop/construct-propagator
          ::template
          (fn [_inputs _outputs network]
            (let [inputs (into {} (map (fn [key]
                                         [key (net/network-cell-strongest network (ports key))]))
                               input-names)
                  outputs (calculate inputs)]
              (mapv (fn [key] (message (ports key) (get outputs key))) output-names)))
          (mapv ports input-names) (mapv ports output-names))
         initial)]
    installed))

(defn- merge-template []
  (template [:content :update] [:out]
            (fn [{:keys [content update]}]
              ;; Reducer cells supply an empty storage map as their identity.
              ;; It is not an incoming observation or an alternative Recording.
              {:out (cond
                      (= {} content) update
                      (= {} update) content
                      :else (merge-recordings content update))})))

(defn- projection-template []
  (template [:slots] [:out :dependence :epoch]
            (fn [{:keys [slots]}]
              {:out (recording-values slots) :dependence #{} :epoch #{}})))

(defn recording-cell-id [output-id]
  (gur/stable-node-id [::recording output-id]))

(defn recorder-states
  "Inspection of this module's owned recorder cells through public abstractions.
  This is not used to evaluate a stage or materialize an application actor."
  [network]
  (into []
        (keep (fn [[_ entry]]
                (when (cell/cell? entry)
                  (let [content (cell/cell-content entry)]
                    (when (reducer/reducer-cell? content)
                      (let [recording (reducer/reducer-slots content)]
                        (when (recording? recording) recording)))))))
        (net/net-env network)))

(defn- install-stage [capacity merge-network projection-network network inputs output]
  (when-not (= 1 (count inputs))
    (throw (ex-info "A history stage expects one input" {:arity (count inputs)})))
  (let [input (first inputs)
        history-id (recording-cell-id output)
        archive-id (gur/stable-node-id [::archive output])
        source-id (gur/stable-node-id [::publication output])
        prepared (reduce nb/ensure-cell network [history-id archive-id output])
        initial (reducer/reducer-cell history-id merge-network projection-network
                                      (new-recording capacity))
        prepared (net/assoc-net-cell prepared history-id
                                     (merge/merge-cell-entry
                                      (net/network-env-lookup prepared history-id) initial prepared))
        record (prop/construct-propagator
                ::record
                (fn [_inputs _outputs current]
                  (let [supported (net/network-cell-strongest current input)
                        payload (data/payload supported)]
                    (cond
                      (value/nothing? payload) []
                      (value/contradiction? payload)
                      (throw (ex-info "history received contradictory information" {:input input}))
                      :else
                      (let [previous (reducer/reducer-slots
                                      (net/network-cell-content current history-id))
                            stamp [payload (datum/support-of supported)
                                   (datum/membership-of supported)]
                            next (record-value previous payload stamp)]
                        (if (= previous next)
                          []
                          [(message history-id
                                    (reducer/reducer-cell history-id merge-network projection-network next))])))))
                [input] [history-id])
        publish (publication/p:observe
                 source-id history-id archive-id
                 (fn [current]
                   (let [selected (net/network-cell-strongest current history-id)]
                     (if (value/nothing? selected)
                       value/nothing
                       (reducer/reduced-result selected)))))
        forward (observation/identity ::window archive-id output)
        [record-id recorded] (record prepared)
        [publication-id published] (publish recorded)
        [forward-id installed] (forward published)]
    [installed [record-id publication-id forward-id] output]))

(defn history-stage
  "Construct a stage. Every application allocates a separate bounded reducer;
  sharing this callable does not share its buffers. No clock or host atom is used."
  [capacity]
  (new-recording capacity)
  (let [merge-network (merge-template)
        projection-network (projection-template)]
    (operator/operator-closure
     {:name ['history capacity]
      :install (partial install-stage capacity merge-network projection-network)})))
