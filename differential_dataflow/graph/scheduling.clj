(ns differential-dataflow.graph.scheduling
  "When to run operators and stepping the graph (no declaration or stream allocation)."
  (:require [differential-dataflow.graph.stream :as stream]))

(defn operator-pending?
  [{:keys [inputs pending?]}]
  (or @pending?
      (some (complement stream/stream-empty?) inputs)))

(defn operator-run!
  [{:keys [run!]}]
  (run!))

(defn operator-frontiers
  [{:keys [input-frontiers output-frontier]}]
  [@input-frontiers @output-frontier])

(defn graph-step!
  "One synchronous pass: invoke each operator's `run!` in declaration order."
  [{:keys [operators]}]
  (doseq [op operators]
    (operator-run! op)))
