(ns propagators.experimental.network-history
  "Explicit finite semantic-network recording and replay values. Time is elapsed milliseconds."
  (:require [propagators.semantic-trace.capture :as capture]
            [propagators.semantic-trace.graph-facts :as facts]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn field [x k]
  (cond
    (capture/fragment? x) (get (capture/root-graph x) k)
    (net/net? x) (obj/accessor-source-slot-value x k)
    :else (get x k)))

(defn recording? [x] (= true (field x :history/recording)))
(defn replay? [x] (= :semantic (field x :replay/type)))
(defn frame-observation [frame] (field frame :frame/observation))

(defn frame
  "Capture frame metadata once. The graph and its values remain opaque."
  [fields]
  (let [graph (field fields :graph)]
    (when-not (and (capture/semantic-graph? graph) (number? (field fields :elapsedMs)))
      (throw (ex-info "History frame requires a semantic graph and elapsed milliseconds" {})))
    (assoc (into {} (map #(vector % (field fields %)))
                 [:sequence :time :elapsedMs :graph :source-support])
           :frame/observation (or (frame-observation fields)
                                  (capture/observation-id graph)
                                  (ids/new-node-id)))))

(defn- history-capture [observation properties frames]
  (let [initial (facts/from-graph observation :root properties #{})
        qualified
        (reduce-kv
         (fn [graph index frame]
           (let [observed (frame-observation frame)
                 premise {:source (gur/stable-node-id [::frame observed (:sequence frame)])
                          :timestamp 0 :premises-status :active}
                 ;; Membership says that this frame was captured. Its live source
                 ;; support is retained in the opaque frame, never joined with a
                 ;; later observation's premise frontier.
                 graph (facts/claim graph :history/frame [index observed] frame #{premise} #{})]
             (if (zero? index)
               graph
               (facts/derive graph
                             {:predicate :history/next :tuple [(dec index) index] :base true
                              :contributors
                              #{(facts/assertion-id observation :root :history/frame
                                                   [(dec index) (frame-observation (nth frames (dec index)))])
                                (facts/assertion-id observation :root :history/frame [index observed])}}))))
         initial frames)]
    (capture/fragment {:observation observation :scopes {:root qualified}
                       :support #{} :provenance [{:capture/kind :history}]})))

(defn replay [frames]
  (let [frames (mapv frame frames)
        identity (mapv (fn [f] [(frame-observation f) (:sequence f) (:time f) (:elapsedMs f)]) frames)]
    (history-capture [::replay identity]
                     {:replay/type :semantic :replay/frames frames} frames)))

(defn empty-replay [] (replay []))

(defn between [recording start end]
  (if (and (recording? recording) (number? start) (number? end) (<= 0 start end))
    (filterv #(<= start (:elapsedMs %) end) (field recording :history/frames))
    (throw (ex-info "history:between expects a recording and 0 <= start <= end in milliseconds"
                    {:start start :end end}))))

(defn append-frame [acc recorded]
  (if (replay? acc)
    (replay (conj (vec (field acc :replay/frames)) (frame recorded)))
    (throw (ex-info "history:append expects a replay and a recorded semantic graph"
                    {:replay? false :acc-type (field acc :replay/type)}))))

(defn- recording-properties [recording]
  (cond-> (into {} (map #(vector % (field recording %)))
                [:history/recording :history/frames :history/start :history/latest])
    (some? (field recording :history/capacity))
    (assoc :history/capacity (field recording :history/capacity))))

(defn next-recording [previous graph source-support now]
  (if (and (recording? previous) (= graph (field previous :history/latest)))
    previous
    (let [previous (if (recording? previous) (recording-properties previous)
                     {:history/recording true :history/frames [] :history/start now})
          frames (field previous :history/frames)
          last-time (get (peek frames) :time (field previous :history/start))
          time (max last-time now)
          captured (frame {:sequence (count frames) :time time
                           :elapsedMs (- time (field previous :history/start))
                           :graph graph :source-support source-support})]
      (assoc previous :history/latest graph :history/frames (conj frames captured)))))

(defn next-bounded-recording
  "Retain the latest capacity changed graphs, with monotonic sequence numbers.
  Capacity is fixed for a recording; elapsed time remains relative to its start."
  [previous graph source-support now capacity]
  (when-not (pos-int? capacity)
    (throw (ex-info "History capacity must be a positive integer" {:capacity capacity})))
  (when (and (recording? previous)
             (not= capacity (field previous :history/capacity)))
    (throw (ex-info "History capacity cannot change within a recording" {})))
  (let [next (next-recording previous graph source-support now)]
    (if (= previous next)
      previous
      (let [frames (field next :history/frames)
            last-sequence (get (peek (field previous :history/frames)) :sequence -1)
            newest (assoc (peek frames) :sequence (inc last-sequence))]
        (assoc next :history/capacity capacity
                    :history/frames (vec (take-last capacity (conj (pop frames) newest))))))))

(defn- captured-support [input graph]
  (apply support/combine (map datum/support-of (cons input (vals (:values (capture/root-graph graph)))))))

(defn- recording-messages [source out network advance]
  (let [input (net/network-cell-strongest network source)
        graph (data/payload input)]
    (cond
      (value/nothing? graph) []
      (not (capture/semantic-graph? graph))
      (throw (ex-info "history:record expects a semantic tracer graph" {}))
      :else
      (let [old (data/payload (net/network-cell-strongest network out))
            next (advance old graph (captured-support input graph) (System/currentTimeMillis))]
        (if (= old next)
          []
          (let [revision (inc (:sequence (peek (:history/frames next))))
                premise {:source out :timestamp revision :premises-status :active}
                captured (history-capture [::recording out revision] next (:history/frames next))]
            [(message out (membership/content {:base captured :support #{premise} :membership #{}}))]))))))

(defn p:record [source out]
  (prop/construct-propagator
   ::record
   (fn [_inputs _outputs network]
     (recording-messages source out network next-recording))
   [source] [out]))

(defn p:record-latest [source capacity-id out]
  (prop/construct-propagator
   ::record-latest
   (fn [_inputs _outputs network]
     (let [capacity (data/payload (net/network-cell-strongest network capacity-id))]
       (if (value/nothing? capacity)
         []
         (recording-messages source out network
                             (fn [previous graph support now]
                               (next-bounded-recording previous graph support now capacity))))))
   [source capacity-id] [out]))
