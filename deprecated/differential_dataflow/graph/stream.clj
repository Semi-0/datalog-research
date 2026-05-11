(ns differential-dataflow.graph.stream
  "Difference stream edge: one deque per reader, DATA / FRONTIER messages."
  (:require [differential-dataflow.graph.frontier :refer [frontier-lte?]]))

(def ^:private message-data :data)
(def ^:private message-frontier :frontier)

(defrecord DifferenceStreamReader [^java.util.ArrayDeque queue])

(defn drain
  "Pop all messages from right end (FIFO for append-left sends). Returns vector."
  [^DifferenceStreamReader r]
  (let [q (.queue r)]
    (loop [out []]
      (if (.isEmpty q)
        out
        (recur (conj out (.pollLast q)))))))

(defn stream-empty?
  [^DifferenceStreamReader r]
  (.isEmpty ^java.util.ArrayDeque (.queue r)))

(defn probe-frontier-less-than?
  "Walk queue left→right without removing. Returns false if any FRONTIER msg
  satisfies `(frontier-lte? query received)` (same early-exit shape as Python)."
  [^DifferenceStreamReader r query]
  (let [q (.queue r)
        it (.iterator q)]
    (loop []
      (if-not (.hasNext it)
        true
        (let [[typ msg] (.next it)]
          (if (and (= typ message-frontier) (frontier-lte? query msg))
            false
            (recur)))))))

(defrecord DifferenceStreamWriter [queues-atom frontier-atom])

(defn difference-stream-writer
  "Create a writer with no readers yet; `frontier-atom` holds current frontier or nil."
  []
  (->DifferenceStreamWriter (atom []) (atom nil)))

(defn new-reader!
  "Attach a new reader queue; returns a `DifferenceStreamReader`."
  [^DifferenceStreamWriter w]
  (let [q (java.util.ArrayDeque.)]
    (swap! (.queues-atom w) conj q)
    (->DifferenceStreamReader q)))

(defn send-data!
  "Broadcast one DATA message `[:data [version collection]]` to every reader queue."
  [^DifferenceStreamWriter w version collection]
  (let [f @(.frontier-atom w)]
    (when f
      (assert (frontier-lte? f version)
              "version must not regress before current writer frontier"))
    (doseq [^java.util.ArrayDeque q @(.queues-atom w)]
      (.addFirst q [message-data [version collection]]))))

(defn send-frontier!
  "Broadcast FRONTIER, then store it as the writer frontier."
  [^DifferenceStreamWriter w frontier]
  (let [f @(.frontier-atom w)]
    (when f
      (assert (frontier-lte? f frontier)
              "frontier must be monotone non-decreasing"))
    (reset! (.frontier-atom w) frontier)
    (doseq [^java.util.ArrayDeque q @(.queues-atom w)]
      (.addFirst q [message-frontier frontier]))))
