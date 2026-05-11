(ns differential-dataflow.graph.runtime
  "Live operator and graph records (after construction)."
  (:require [differential-dataflow.graph.stream :as stream]))

(defrecord Operator
  [inputs output-writer run! pending? input-frontiers output-frontier])

(defn unary-operator
  "One input reader, one output writer, no-arg `run!`, initial frontier value."
  [input-r output-w run! initial-frontier]
  (->Operator [input-r]
              output-w
              run!
              (atom false)
              (atom [initial-frontier])
              (atom initial-frontier)))

(defn unary-input-messages [^Operator op]
  (stream/drain (first (:inputs op))))

(defn unary-input-frontier [^Operator op]
  (first @(:input-frontiers op)))

(defn unary-set-input-frontier!
  [^Operator op frontier]
  (swap! (:input-frontiers op) assoc 0 frontier))

(defn binary-operator
  "Two input readers, one output writer."
  [input-a input-b output-w run! initial-frontier]
  (->Operator [input-a input-b]
              output-w
              run!
              (atom false)
              (atom [initial-frontier initial-frontier])
              (atom initial-frontier)))

(defn binary-input-a-messages [^Operator op] (stream/drain (first (:inputs op))))
(defn binary-input-b-messages [^Operator op] (stream/drain (second (:inputs op))))
(defn binary-input-a-frontier [^Operator op] (first @(:input-frontiers op)))
(defn binary-input-b-frontier [^Operator op] (second @(:input-frontiers op)))

(defn binary-set-input-a-frontier! [^Operator op f]
  (swap! (:input-frontiers op) assoc 0 f))

(defn binary-set-input-b-frontier! [^Operator op f]
  (swap! (:input-frontiers op) assoc 1 f))

(defrecord Graph [stream-writers operators])

(defn graph
  "`stream-writers` is a map keyword → writer (for ingress and inspection);
  `operators` are runtime `Operator` records."
  [stream-writers operators]
  (->Graph stream-writers operators))
