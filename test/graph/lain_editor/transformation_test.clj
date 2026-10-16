(ns graph.lain-editor.transformation-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.runtime :as runtime]
            [graph.lain-editor.inspection :as inspection]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.env :as env]
            [propagators.network :as net]))

(defn request [editors]
  {:pane "a" :generation (:generation (get @editors "a"))
   :display {:cell "@display" :mode "graph" :direction "both"}})

(defn node [graph label kind]
  (first (filter #(and (= label (:label %)) (= kind (:kind %))) (:nodes graph))))

(deftest definitions-name-transformation-results-without-a-definition-node
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source
        "(define make-adder
           (network [bias]
             (network [x]
               (+ x bias))))
         (define input 5)
         (define add-ten (make-adder 10))
         (define result (add-ten input))
         (display (relationship:roots-semantic input))"})
      (let [req (request editors) network (:program/net @(:session (get @editors "a")))
            graph (:graph (inspection/inspect editors req))
            edges (set (map (juxt :from :to) (:edges graph)))
            factory (node graph "make-adder" "propagator")
            binding (node graph "add-ten" "cell")
            call (node graph "add-ten" "propagator")
            result (node graph "result" "cell")]
        (is (not-any? #(contains? #{"define" "+" "(make-adder 10)" "(add-ten input)"} (:label %)) (:nodes graph)))
        (is (contains? edges [(:id factory) (:id binding)]))
        (is (contains? edges [(:id binding) (:id call)]))
        (is (contains? edges [(:id call) (:id result)]))
        (is (= 2 (count (:aliases graph))))
        (doseq [[source targets] (:aliases graph)]
          (is (= 1 (count targets)))
          (is (not-any? #(= source (:id %)) (:nodes graph)))
          (is (= source (get-in (inspection/inspect editors (assoc req :node source)) [:selected :id]))))
        (is (identical? network (:program/net @(:session (get @editors "a"))))))
      (finally (runtime/close! editors)))))

(deftest independently-named-cells-retain-copy-flow-and-distinct-state
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source
        "(define input 5)
         (define first (+ input 1))
         (define second first)
         (-> 9 second)
         (display (relationship:roots-semantic input))"})
      (let [req (request editors) graph (:graph (inspection/inspect editors req))
            first-cell (node graph "first" "cell") second-cell (node graph "second" "cell")
            edges (set (map (juxt :from :to) (:edges graph)))
            copies (filter #(and (= "propagator" (:kind %)) (= "->" (:label %))) (:nodes graph))
            state @(:session (get @editors "a")) network (:program/net state)
            binding-id #(env/resolve-binding-id network (:program/env state) %)]
        (is (some? first-cell)) (is (some? second-cell))
        (is (not= (:id first-cell) (:id second-cell)))
        (is (not-any? #(= "define" (:label %)) (:nodes graph)))
        (is (some #(and (contains? edges [(:id first-cell) (:id %)])
                        (contains? edges [(:id %) (:id second-cell)])) copies))
        (is (= 6 (net/network-cell-strongest network (binding-id 'first))))
        (is (value/contradiction? (net/network-cell-strongest network (binding-id 'second)))))
      (finally (runtime/close! editors)))))
