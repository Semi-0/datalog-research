(ns graph.lain-editor.inout-trace-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.runtime :as runtime]
            [graph.lain-editor.display :as display]
            [graph.lain-editor.inspection :as inspection]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.semantic-trace :as trace]
            [propagators.semantic-trace.directions :as directions]))

(defn request [editors]
  {:pane "a" :generation (:generation (get @editors "a"))
   :display {:cell "@display" :mode "graph" :direction "both"}})

(deftest returned-inputs-use-one-caller-cell-and-one-reciprocal-connection
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source (slurp "examples/lain/semantic_tracing/07-two-outputs.lain")})
      (let [network (:program/net @(:session (get @editors "a")))
            raw (data/payload (net/network-cell-strongest network display/cell-id))
            graph (:graph (inspection/inspect editors (request editors)))
            labels (into {} (map (juxt :id :label)) (:nodes graph))
            both (filter :bidirectional (:edges graph))
            raw-edges (set (:edges raw))]
        (is (= 10 (count (:nodes graph))))
        (is (= #{"stats" "+" "->"} (set (map :label (filter #(= "propagator" (:kind %)) (:nodes graph))))))
        (is (= #{#{"stats" "sum"} #{"stats" "product"}}
               (set (map #(set (map labels [(:from %) (:to %)])) both))))
        (is (= 2 (count both)))
        (is (= 1 (count (filter #(= "sum" (:label %)) (:nodes graph)))))
        (is (= 1 (count (filter #(= "product" (:label %)) (:nodes graph)))))
        (is (= 4 (count (:bidirectional-edges raw))))
        (is (every? #(and (raw-edges %) (raw-edges (vec (reverse %)))) (:bidirectional-edges raw)))
        (doseq [[source targets] (:node-aliases raw)
                :when (contains? #{'sum 'product} (get (:nodes raw) (first targets)))]
          (is (= 1 (count targets)))
          (is (contains? (net/net-env network) (second source))))
        (is (identical? network (:program/net @(:session (get @editors "a"))))))
      (finally (runtime/close! editors)))))

(deftest ordinary-returned-members-remain-distinct-and-one-way
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source
        "(define pair (network [x] (list (+ x 1) (* x 2))))
         (define input 3)
         (def-cells result)
         (-> (pair input) result)
         (display (relationship:roots-semantic input))"})
      (let [graph (:graph (inspection/inspect editors (request editors)))]
        (is (not-any? :bidirectional (:edges graph)))
        (is (some #(= "4" (:value %)) (:nodes graph)))
        (is (some #(= "6" (:value %)) (:nodes graph)))
        (is (= 1 (count (filter #(= "input" (:label %)) (:nodes graph))))))
      (finally (runtime/close! editors)))))

(deftest reciprocal-presentation-preserves-directed-traversal-and-union
  (let [graph (directions/annotate
               (trace/graph-union {:nodes {:a 'a :b 'b :c 'c}
                                   :edges [[:a :b] [:b :a] [:b :c]]}))
        shown (directions/connections graph)
        union (trace/graph-union graph)
        upstream (trace/trace-graph union {:node :c :direction :upstream})]
    (is (= 3 (count (:edges graph))))
    (is (= 2 (count shown)))
    (is (= [{:from :a :to :b :bidirectional true} {:from :b :to :c}] shown))
    (is (= (:bidirectional-edges graph) (:bidirectional-edges union)))
    (is (= (set (:edges graph)) (set (:edges upstream))))
    (is (= (:bidirectional-edges graph) (:bidirectional-edges upstream)))))
