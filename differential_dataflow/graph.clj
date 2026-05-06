(ns differential-dataflow.graph
  "Barrel namespace: declaration, construction, and scheduling live under
  `differential-dataflow.graph.{declaration,construction,scheduling}`; stream and
  runtime under `graph.stream` and `graph.runtime`. Use this ns for a flat API."
  (:require [differential-dataflow.graph.construction :as construction]
            [differential-dataflow.graph.declaration :as declaration]
            [differential-dataflow.graph.frontier :as frontier]
            [differential-dataflow.graph.runtime :as runtime]
            [differential-dataflow.graph.scheduling :as scheduling]
            [differential-dataflow.graph.stream :as stream]))

;;; Frontier
(def frontier-lte? frontier/frontier-lte?)

;;; Stream edge
(def difference-stream-writer stream/difference-stream-writer)
(def new-reader! stream/new-reader!)
(def drain stream/drain)
(def stream-empty? stream/stream-empty?)
(def probe-frontier-less-than? stream/probe-frontier-less-than?)
(def send-data! stream/send-data!)
(def send-frontier! stream/send-frontier!)

;;; Runtime records & operator wiring
(def ->Operator runtime/->Operator)
(def map->Operator runtime/map->Operator)
(def unary-operator runtime/unary-operator)
(def unary-input-messages runtime/unary-input-messages)
(def unary-input-frontier runtime/unary-input-frontier)
(def unary-set-input-frontier! runtime/unary-set-input-frontier!)
(def binary-operator runtime/binary-operator)
(def binary-input-a-messages runtime/binary-input-a-messages)
(def binary-input-b-messages runtime/binary-input-b-messages)
(def binary-input-a-frontier runtime/binary-input-a-frontier)
(def binary-input-b-frontier runtime/binary-input-b-frontier)
(def binary-set-input-a-frontier! runtime/binary-set-input-a-frontier!)
(def binary-set-input-b-frontier! runtime/binary-set-input-b-frontier!)
(def ->Graph runtime/->Graph)
(def map->Graph runtime/map->Graph)
(def graph runtime/graph)

;;; Declaration (pure data)
(def unary-op-decl declaration/unary-op-decl)
(def binary-op-decl declaration/binary-op-decl)
(def graph-decl declaration/graph-decl)
(def declared-stream-ids declaration/declared-stream-ids)
(def validate-graph-decl! declaration/validate-graph-decl!)

;;; Construction
(def instantiate-graph! construction/instantiate-graph!)

;;; Scheduling
(def operator-pending? scheduling/operator-pending?)
(def operator-run! scheduling/operator-run!)
(def operator-frontiers scheduling/operator-frontiers)
(def graph-step! scheduling/graph-step!)

(comment
  ;; Physical edge only:
  (def out (difference-stream-writer))
  (def r (new-reader! out))
  (send-data! out 0 [[:x 1]])
  (send-frontier! out 1)
  (drain r)
  ;; => [[:data [0 [[:x 1]]]] [:frontier 1]]
  )
