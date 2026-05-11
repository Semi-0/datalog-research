(ns differential-dataflow.async-graph
  (:require [clojure.core.async :as a]))

(defn drain!!
  "Drain all immediately available values from channel."
  [ch]
  (loop [xs []]
    (a/alt!!
      ch ([v] (if (some? v) (recur (conj xs v)) xs))
      :default xs)))


(defn take-burst!!
  "Block for one value, then drain the rest of the current burst."
  [ch]
  (when-some [v (a/<!! ch)]
    (into [v] (drain!! ch))))

(defn edge
  "A broadcast edge carrying [:data t coll] and [:frontier t] messages."
  ([] (edge 128))
  ([buf]
   (let [in       (a/chan buf)
         mult     (a/mult in)
         frontier (atom nil)]
     {:in in
      :mult mult
      :frontier frontier})))

(defn tap!
  "Create a reader channel for an edge."
  ([e] (tap! e 128))
  ([e buf]
   (let [ch (a/chan buf)]
     (a/tap (:mult e) ch)
     ch)))

(defn send! [e msg]
  (let [[tag t] msg
        f @(:frontier e)]
    (case tag
      :data
      (when (and f (< (long t) (long f)))
        (throw (ex-info "data sent behind frontier"
                        {:frontier f :version t})))

      :frontier
      (do
        (when (and f (< (long t) (long f)))
          (throw (ex-info "frontier regression"
                          {:frontier f :new-frontier t})))
        (reset! (:frontier e) t))

      nil)
    (a/>!! (:in e) msg)))

(defn send-data! [e t coll]
  (send! e [:data t coll]))

(defn send-frontier! [e t]
  (send! e [:frontier t]))

(defn operator
  "Create a manually stepped operator.

  `inputs` is a seq of reader channels.
  `outputs` is whatever the handler expects, usually one edge or a map of edges.

  Handler receives:

    batches outputs

  where `batches` is a vector of drained batches, one per input."
  [{:keys [id inputs outputs handler]}]
  {:id id
   :inputs inputs
   :outputs outputs
   :step!
   (fn []
     (let [batches (mapv drain!! inputs)]
       (when (some seq batches)
         (handler batches outputs))))})

(defn actor!
  "Run an operator continuously. Blocks on the first input, then drains all inputs."
  [{:keys [inputs outputs handler]}]
  (let [first-input (first inputs)]
    (a/go-loop []
      (when-some [v (a/<! first-input)]
        (let [batches (into [(into [v] (drain!! first-input))]
                            (map drain!! (rest inputs)))]
          (handler batches outputs))
        (recur)))))

(defn graph [& operators]
  {:operators (vec operators)})

(defn step! [g]
  (doseq [op (:operators g)]
    ((:step! op))))