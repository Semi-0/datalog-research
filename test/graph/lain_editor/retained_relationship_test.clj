(ns graph.lain-editor.retained-relationship-test
  (:require [clojure.test :refer [deftest is]]
            [graph.json :as json]
            [graph.lain-editor.runtime :as editor]
            [graph.lain-editor.display :as display]
            [graph.lain-editor.inspection :as inspection]
            [graph.lain-editor.retained-inspection :as child]
            [graph.lain-editor.workspace :as workspace]
            [propagators.compiler-2.runtime.retained-network :as retained]
            [propagators.compiler-2.runtime.operators.relationship-observer :as original]
            [propagators.compiler-2.model.operator-value :as operators]
            [propagators.experimental.visualization.data :as data]
            [propagators.network :as net]
            [propagators.network-builder :as builder]
            [propagators.ids :as ids]))

(def directory "examples/lain/semantic_tracing/retained/")
(defn- graph [pane]
  (data/payload (net/network-cell-strongest (:program/net @(:session pane)) display/cell-id)))

(deftest retained-gallery-has-correct-scope-and-readable-values
  (let [document (json/read-json (slurp (str directory "workspace.json")))
        editors (atom {})]
    (is (= document (workspace/validate document)))
    (try
      (editor/restore! editors document)
      (doseq [{:keys [id source filename display]} (:panes document)]
        (is (= source (slurp (str directory filename))))
        (let [pane (get @editors id) g (graph pane)
              labels (set (map str (vals (:nodes g))))
              result (inspection/inspect editors {:pane id :generation (:generation pane) :display display})]
          (is (seq (:nodes g)))
          (is (= "ready" (:status result)))
          (is (contains? (:graph/modifiers g) :cell-values))
          (case id
            "09-semantic" (do (is (= :root (:dataflow/scope g)))
                               (is (labels "combine")) (is (not (labels "*"))))
            "10-roots" (do (is (= :root (:dataflow/scope g)))
                            (is (labels "combine")) (is (not (labels "*"))))
            "11-children" (do (is (vector? (:dataflow/scope g)))
                               (is (labels "*")) (is (labels "+")) (is (not (labels "combine"))))
            (is false "Unknown retained gallery example"))
          (is (= "10" (:value (inspection/inspect editors
                                      {:pane id :generation (:generation pane)
                                       :display {:cell "result" :mode "value"}}))))))
      (finally (editor/close! editors)))))

(deftest child-inspection-reuses-private-topology-without-changing-the-program
  (let [pane (editor/prepare (slurp (str directory "09-semantic.lain")) [])
        session (:session pane) network (:program/net @session)
        parent (first (for [[id label] (:nodes (graph pane)) :when (= "combine" (str label))] id))
        compiled (atom []) step retained/step]
    (try
      (with-redefs [retained/step (fn [& arguments]
                                  (let [result (apply step arguments)]
                                    (swap! compiled conj (:compiled? result)) result))]
        (let [first (child/child-graph session parent)
              second (child/child-graph session parent)]
          (is (= first second))
          (is (= [true] @compiled))
          (is (identical? network (:program/net @session)))
          (swap! session assoc :program/net (builder/install-cell network (ids/new-node-id) 42 42))
          (is (= first (child/child-graph session parent)))
          (is (= [true false] @compiled))
          (is (= #{"*" "+"}
                 (into #{} (comp (filter #(contains? #{"*" "+"} (str %))) (map str))
                       (vals (:nodes first)))))))
      (finally (editor/close-session! session)))
    (is (empty? @(get @session child/storage-key)))))

(deftest previous-operator-definitions-remain-callable
  (doseq [definition [(original/roots-operator) (original/semantic-operator)
                      (original/roots-semantic-operator) (original/dataflow-operator)]]
    (is (fn? (operators/operator-install definition)))))
