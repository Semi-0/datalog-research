(ns propagators.experimental.network-history
  "Explicit finite semantic-network recording and replay values. Time is elapsed milliseconds."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.support :as support]
            [propagators.experimental.visualization.data :as data]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.semantic-trace :as trace]))

(defn field [x k]
  (if (net/net? x) (obj/accessor-source-slot-value x k) (get x k)))

(defn recording? [x] (= true (field x :history/recording)))
(defn replay? [x] (= :semantic (field x :replay/type)))
(defn empty-replay [] {:replay/type :semantic :replay/frames []})

(defn between [recording start end]
  (if (and (recording? recording) (number? start) (number? end) (<= 0 start end))
    (filterv #(<= start (:elapsedMs %) end) (field recording :history/frames))
    (throw (ex-info "history:between expects a recording and 0 <= start <= end in milliseconds"
                    {:start start :end end}))))

(defn append-frame [acc frame]
  (let [graph (field frame :graph)
        frame (into {} (map #(vector % (field frame %)))
                    [:sequence :time :elapsedMs :graph :source-support])]
    (if (and (replay? acc) (trace/semantic-trace-graph? graph) (number? (:elapsedMs frame)))
      {:replay/type :semantic :replay/frames (conj (vec (field acc :replay/frames)) frame)}
      (throw (ex-info "history:append expects a replay and a recorded semantic graph"
                      {:replay? (replay? acc) :acc-type (field acc :replay/type)
                       :graph-class (str (class graph)) :graph-flag (field graph :semantic-trace/graph)
                       :elapsed (:elapsedMs frame)})))))

(defn next-recording [previous graph source-support now]
  (if (and (recording? previous) (= graph (field previous :history/latest)))
    previous
    (let [previous (if (recording? previous) previous
                     {:history/recording true :history/frames [] :history/start now})
          frames (field previous :history/frames)
          last-time (get (peek frames) :time (field previous :history/start))
          time (max last-time now)
          frame {:sequence (count frames) :time time
                 :elapsedMs (- time (field previous :history/start))
                 :graph graph :source-support source-support}]
      (assoc previous :history/latest graph :history/frames (conj frames frame)))))

(defn- captured-support [input graph]
  (apply support/combine (map datum/support-of (cons input (vals (:values graph))))))

(defn p:record [source out]
  (prop/construct-propagator
   ::record
   (fn [_inputs _outputs network]
     (let [input (net/network-cell-strongest network source)
           graph (data/payload input)]
       (cond
         (value/nothing? graph) []
         (not (trace/semantic-trace-graph? graph))
         (throw (ex-info "history:record expects a semantic tracer graph" {}))
         :else
         (let [old (data/payload (net/network-cell-strongest network out))
               next (next-recording old graph (captured-support input graph) (System/currentTimeMillis))]
           (if (= old next)
             []
             (let [premise {:source out :timestamp (count (:history/frames next)) :premises-status :active}]
               [(message out (membership/content {:base next :support #{premise} :membership #{}}))]))))))
   [source] [out]))
