(ns graph.lain-editor.semantic-gallery-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [graph.json :as json]
            [graph.lain-editor.workspace :as workspace]
            [graph.lain-editor.runtime :as runtime]
            [graph.lain-editor.display :as display]
            [graph.lain-editor.inspection :as inspection]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.application-ports :as ports]
            [propagators.gur.flat :as gur]
            [propagators.semantic-trace :as trace]
            [propagators.network :as net]
            [propagators.experimental.visualization.data :as data]))

(def expected
  {"01-unary" "true" "02-binary" "7" "03-three-inputs" "10"
   "04-branch-true" "8" "05-branch-false" "14" "06-recursion" "10"
   "07-two-outputs" "19" "08-captured-closure" "15"})

(def root-contracts
  {"01-unary" {"not" [#{"flag"} #{"result"}]}
   "02-binary" {"+" [#{"left" "right"} #{"result"}]}
   "03-three-inputs" {"combine" [#{"a" "b" "c"} #{"result"}]}
    "04-branch-true" {"if" [#{"flag" "8" "14"} #{"result"}]
                        "+" [#{"x" "1"} #{"8"}] "*" [#{"x" "2"} #{"14"}]}
    "05-branch-false" {"if" [#{"flag" "8" "14"} #{"result"}]
                         "+" [#{"x" "1"} #{"8"}] "*" [#{"x" "2"} #{"14"}]}
   "06-recursion" {"sum-to" [#{"input" "0"} #{"result"}]}
   "07-two-outputs" {"+" [#{"sum" "product"} #{"result"}]
                      "stats" [#{"3" "4" "sum" "product"} #{"cell" "7" "12"}]}
    "08-captured-closure" {"add-ten" [#{"input" "add-ten"} #{"result"}]}})

(defn directed-edges [graph]
  (mapcat (fn [{:keys [from to bidirectional] :as edge}]
            (if bidirectional [edge {:from to :to from}] [edge])) (:edges graph)))

(defn assert-root-contract [pane graph network]
  (let [calls (filter #(= "propagator" (:kind %)) (:nodes graph))
        semantic-calls (remove #(contains? #{"->" "<->" "define" "copy"} (:label %)) calls)
        topologies (into {} (map (fn [app]
                                   [(pr-str [[:outer] (gur/stable-node-id [(:application-id app) :apply-prop])]) app]))
                         (application/application-topologies network))
        kinds (into {} (map (juxt :id :kind)) (:nodes graph))]
    (is (= (frequencies (keys (get root-contracts pane))) (frequencies (map :label semantic-calls))))
    (is (= 1 (count (filter #(= "->" (:label %)) calls))))
    (is (not-any? #(= "define" (:label %)) calls))
    (is (every? #(= #{"cell" "propagator"} #{(kinds (:from %)) (kinds (:to %))}) (:edges graph)))
    (is (not-any? #(contains? % :caption) (:nodes graph)))
    (doseq [{:keys [id label]} semantic-calls]
      (let [app (get topologies id)
            inputs (cond-> (set (:argument-ids app))
                     (= "add-ten" label) (conj (:operator-id app)))
            outputs (:outputs (ports/application-ports network app))
            qualify (fn [node]
                      (let [id (pr-str [[:outer] node]) targets (get (:aliases graph) id)]
                        (if targets
                          (do (is (= 1 (count targets))) (first targets))
                          id)))]
        (is (= (set (map qualify inputs))
               (set (for [edge (directed-edges graph) :when (= id (:to edge))] (:from edge)))))
        (is (= (set (map qualify outputs))
               (set (for [edge (directed-edges graph) :when (= id (:from edge))] (:to edge)))))))))

(defn reachable-labels [graph start]
  (let [next-nodes (group-by :from (directed-edges graph))
        labels (into {} (map (juxt :id :label)) (:nodes graph))]
    (loop [pending [start] visited #{}]
      (if-let [id (peek pending)]
        (if (contains? visited id)
          (recur (pop pending) visited)
          (recur (into (pop pending) (map :to (get next-nodes id))) (conj visited id)))
        (set (map labels visited))))))

(def directory "examples/lain/semantic_tracing/")

(defn inspect-published-trace [editors request]
  (with-redefs [dataflow/dataflow-graph
                (fn [& _]
                  (throw (ex-info "Gallery inspection must use the published tracer graph" {})))]
    (inspection/inspect editors request)))

(deftest every-gallery-file-and-workspace-displays-the-unified-tracer
  (let [files (into #{}
                    (comp (filter #(.isFile %))
                          (map #(.getName %))
                          (filter #(str/ends-with? % ".lain")))
                    (.listFiles (io/file directory)))]
    (doseq [filename ["workspace.json" "single-desktop-v1.json"]]
      (testing filename
        (let [document (json/read-json (slurp (str directory filename)))
              panes (:panes document)]
          (workspace/validate document)
          (is (= (set (keys expected)) (set (map :id panes))))
          (is (= files (set (map :filename panes))))
          (doseq [pane panes]
            (testing (:filename pane)
              (let [source (slurp (str directory (:filename pane)))
                    forms (loader/source-forms source)
                    displayed (last forms)]
                (is (= source (:source pane) (:executedSource pane)))
                (is (= {:cell "@display" :mode "graph" :direction "both"}
                       (:display pane)))
                (is (= 'display (first displayed)))
                (is (= 2 (count displayed)))
                 (is (= 'observe-dependents-retained (first (second displayed))))
                (is (= 2 (count (second displayed))))
                (is (some #(= 'def-cells (first %)) forms))))))))))

(deftest gallery-programs-and-real-traces
  (let [directory "examples/lain/semantic_tracing/"
        document (json/read-json (slurp (str directory "workspace.json")))
        editors (atom {})]
    (is (= document (workspace/validate document)))
    (try
      (runtime/restore! editors document)
      (doseq [pane (:panes document)]
        (testing (:id pane)
          (is (= (:source pane) (slurp (str directory (:filename pane)))))
          (let [request {:pane (:id pane) :generation (:generation (get @editors (:id pane)))
                         :display (:display pane)}
                result (inspect-published-trace editors request)
                graph (:graph result) ids (set (map :id (:nodes graph)))
                calls (filter #(= "propagator" (:kind %)) (:nodes graph))]
            (is (= "leapfrog" (:projection graph)))
            (is (= {:kind "root"} (:scope graph)))
             (is (= "network" (:representation graph)))
             (is (not-any? #(= "cell" (:label %)) (:nodes graph))
                 "Compiler names every visible literal, return and output port")
            (assert-root-contract (:id pane) graph (:program/net @(:session (get @editors (:id pane)))))
            (let [session (:session (get @editors (:id pane))) network (:program/net @session)]
              (doseq [node (:nodes graph)]
                (let [selected (:selected (inspection/inspect editors (assoc request :node (:id node))))]
                  (is (= (:id node) (:id selected)))
                  (is (= (:kind node) (:kind selected)))))
              (is (= graph (:graph (inspect-published-trace editors request))))
              (is (identical? network (:program/net @session))))
            (let [state @(:session (get @editors (:id pane))) network (:program/net state)
                  id display/cell-id
                  published (data/payload (net/network-cell-strongest network id))]
              (is (trace/semantic-trace-graph? published))
              (is (= :leapfrog (:dataflow/projection published)))
               (is (= :root (:dataflow/scope published)))
               (is (= :downstream (:traversal/direction published)))
              (is (identical? published (inspection/semantic-graph network id)))
              (is (= (inspection/graph-json published network) graph)
                  "Render the returned tracer cell with the same live slot context"))
            (is (= (get expected (:id pane))
                   (:value (inspection/inspect editors (assoc request :display {:cell "result" :mode "value"})))))
            (is (= "ready" (:status result)))
            (is (seq calls))
            (is (every? #(and (ids (:from %)) (ids (:to %))) (:edges graph)))
            (println (:id pane) "=" (get expected (:id pane))
                     "nodes" (count ids) "calls" (mapv :label calls))
            (doseq [call (filter #(contains? #{"combine" "sum-to" "stats" "add-ten"} (:label %)) calls)]
              (let [child (:graph (inspection/inspect editors (assoc request :parent (:id call))))]
                (is (seq (:nodes child)))
                (let [labels (into {} (map (juxt :id :label)) (:nodes graph))
                      outgoing (set (for [edge (:edges graph) :when (= (:id call) (:from edge))]
                                      (get labels (:to edge))))
                      incoming (set (for [edge (:edges graph) :when (= (:id call) (:to edge))]
                                      (get labels (:from edge))))
                      child-calls (set (map :label (filter #(= "propagator" (:kind %)) (:nodes child))))]
                  (case (:label call)
                    "combine" (is (every? incoming ["a" "b" "c"]))
                    "stats" (is (every? (reachable-labels graph (:id call)) ["sum" "product"]))
                    "sum-to" (is (every? child-calls ["if" "<="]))
                    "add-ten" (is (some #(= "10" (:value %)) (:nodes child)))
                    (is false "Unrecognized example call")))
                (println "  body" (:label call) "calls"
                         (mapv :label (filter #(= "propagator" (:kind %)) (:nodes child)))))))))
      (finally (runtime/close! editors)))))

(deftest accumulator-recursion-values-and-runtime-size
  (let [source (slurp "examples/lain/semantic_tracing/06-recursion.lain") editors (atom {})]
    (try
      (doseq [[input expected] [[0 "0"] [1 "1"] [4 "10"] [8 "36"]]]
        (let [program (str/replace source "(define input 4)" (str "(define input " input ")"))
              {:keys [generation]} (runtime/run! editors {:pane "tail" :source program})
              result (inspection/inspect editors {:pane "tail" :generation generation
                                                  :display {:cell "result" :mode "value"}})]
          (is (= "ready" (:status result))) (is (= expected (:value result)))
          (println "accumulator n" input "runtime nodes" (:runtimeNodes result))))
      (finally (runtime/close! editors)))))

(deftest accumulator-occurrences-and-selected-operator-connectivity
  (let [editors (atom {})]
    (try
      (let [{:keys [generation]} (runtime/run! editors {:pane "tail" :source (slurp "examples/lain/semantic_tracing/06-recursion.lain")})
            request {:pane "tail" :generation generation :display {:cell "@display" :mode "graph" :direction "upstream"}}
            root (:graph (inspection/inspect editors request))
            first-call (first (filter #(= "sum-to" (:label %)) (:nodes root)))]
        (loop [call first-call expected-n 4 expected-acc 0 seen #{}]
          (is (some? call)) (is (not (contains? seen (:id call))))
          (let [body (:graph (inspection/inspect editors (assoc request :parent (:id call))))
                n (first (filter #(= "n" (:label %)) (:nodes body)))
                acc (first (filter #(= "acc" (:label %)) (:nodes body)))
                chooser (first (filter #(= "if" (:label %)) (:nodes body)))
                selected (first (filter #(= ":propagator/anonymous" (:label %)) (:nodes body)))
                chosen (set (for [edge (:edges body) :when (= (:id chooser) (:from edge))] (:to edge)))
                used (set (for [edge (:edges body) :when (= (:id selected) (:to edge))] (:from edge)))
                branch (:graph (inspection/inspect editors (assoc request :parent (:id selected))))
                branch-calls (filter #(and (= "propagator" (:kind %))
                                          (not (contains? #{"->" "<->" "define" "copy"} (:label %))))
                                     (:nodes branch))
                next-call (first (filter #(= "sum-to" (:label %)) branch-calls))]
            (is (= (str expected-n) (:value n)))
            (is (= (str expected-acc) (:value acc)))
            (is (some chosen used) "if-selected callable feeds the dynamic application")
            (if (pos? expected-n)
              (do (is (= #{"-" "+" "sum-to"} (set (map :label branch-calls))))
                  (recur next-call (dec expected-n) (+ expected-acc expected-n) (conj seen (:id call))))
              (do (is (empty? branch-calls)) (is (nil? next-call)))))))
      (finally (runtime/close! editors)))))
