(ns graph.lain-editor.semantic-scope-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.runtime :as runtime]
            [graph.lain-editor.inspection :as inspection]
            [propagators.relationship-observer :as observer]))

(def factory "(define make-adder (network [bias] (network [x] (+ x bias))))")
(def pipeline "(define pipeline (network [x]
                  (define add-ten (make-adder 10)) (add-ten x)))")
(defn observed [program]
  (str program " (def-cells raw) (relationship:roots input raw) (display (relationship:dataflow raw))"))
(defn calls [graph]
  (set (remove #{"->" "<->" "define" "copy"}
               (map :label (filter #(= "propagator" (:kind %)) (:nodes graph))))))
(defn call [graph label]
  (first (filter #(and (= "propagator" (:kind %)) (= label (:label %))) (:nodes graph))))
(defn request [editors]
  {:pane "a" :generation (:generation (get @editors "a"))
   :display {:cell "@display" :mode "graph" :direction "both"}})

(deftest closure-factory-and-use-are-one-root-chain
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source (observed (str factory
        " (define add-ten (make-adder 10)) (define input 5) (define result (add-ten input))"))})
      (let [req (request editors) graph (:graph (inspection/inspect editors req))
            labels (into {} (map (juxt :id :label)) (:nodes graph))
            edges (set (map #(vector (labels (:from %)) (labels (:to %))) (:edges graph)))
            before (:program/net @(:session (get @editors "a")))]
        (is (= "leapfrog" (:projection graph)))
        (is (= {:kind "root"} (:scope graph)))
        (is (some #(and (= "add-ten" (:label %)) (= "network [x]" (:value %))) (:nodes graph)))
        (is (= #{"make-adder" "add-ten"} (calls graph)))
        (is (contains? edges ["make-adder" "add-ten"]))
        (is (contains? edges ["add-ten" "result"]))
        (is (= graph (:graph (inspection/inspect editors req))))
        (is (identical? before (:program/net @(:session (get @editors "a"))))))
      (finally (runtime/close! editors)))))

(deftest opening-a-call-selects-exactly-one-level
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source (observed (str factory " " pipeline
        " (define input 5) (define result (pipeline input))"))})
      (let [req (request editors) root (:graph (inspection/inspect editors req))
            body (:graph (inspection/inspect editors (assoc req :parent (:id (call root "pipeline")))))
            factory-body (:graph (inspection/inspect editors (assoc req :parent (:id (call body "make-adder")))))
            adder-body (:graph (inspection/inspect editors (assoc req :parent (:id (call body "add-ten")))))]
        (is (= "child" (get-in body [:scope :kind])))
        (is (= #{"pipeline"} (calls root)))
        (is (= #{"make-adder" "add-ten"} (calls body)))
        (is (empty? (calls factory-body)))
        (is (= #{"+"} (calls adder-body)))
        (is (some #(= "10" (:value %)) (:nodes adder-body))))
      (finally (runtime/close! editors)))))

(deftest recursion-stops-at-each-explicit-call-boundary
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source (slurp "examples/lain/semantic_tracing/06-recursion.lain")})
      (let [req (request editors) root (:graph (inspection/inspect editors req))
            body (:graph (inspection/inspect editors (assoc req :parent (:id (call root "sum-to")))))
            selected (call body ":propagator/anonymous")
            branch (:graph (inspection/inspect editors (assoc req :parent (:id selected))))]
        (is (= #{"sum-to"} (calls root)))
        (is (= #{"if" "<=" ":propagator/anonymous"} (calls body)))
        (is (= #{"-" "+" "sum-to"} (calls branch)))
        (is (not= (:id (call root "sum-to")) (:id (call branch "sum-to")))))
      (finally (runtime/close! editors)))))

(deftest inspecting-a-call-does-not-chase-children-without-request
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source (slurp "examples/lain/semantic_tracing/06-recursion.lain")})
      (let [req (request editors) graph (:graph (inspection/inspect editors req))
            selected (assoc req :node (:id (call graph "sum-to")))
            boundary (:call (inspection/inspect editors selected))
            chased (:call (inspection/inspect editors (assoc selected :chase true)))]
        (is (= 1 (count (:occurrences boundary))))
        (is (empty? (get-in boundary [:occurrences 0 :operations])))
        (is (false? (:chased boundary)))
        (is (get-in boundary [:occurrences 0 :hasChildren]))
        (is (= 5 (count (filter :sameCallable (:occurrences chased)))))
        (is (:chased chased)))
      (finally (runtime/close! editors)))))

(deftest appended-top-level-call-refreshes-the-shared-published-trace
  (let [editors (atom {})]
    (try
      (runtime/run! editors {:pane "a" :source (observed "(define input 5) (define result (+ input 1))")})
      (runtime/extend! editors {:pane "a" :generation (:generation (get @editors "a"))
                               :source "(define doubled (* result 2))"})
      (let [graph (:graph (inspection/inspect editors (request editors)))]
        (let [network (:program/net @(:session (get @editors "a")))
              [_ refreshed] (observer/refresh-roots network)]
          (is (= network refreshed)))
        (is (= #{"+" "*"} (calls graph)))
        (is (some #(and (= "doubled" (:label %)) (= "12" (:value %))) (:nodes graph))))
      (finally (runtime/close! editors)))))
