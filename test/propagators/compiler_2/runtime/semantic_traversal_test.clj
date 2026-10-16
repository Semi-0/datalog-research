(ns propagators.compiler-2.runtime.semantic-traversal-test
  (:require [clojure.test :refer [deftest is]]
            [graph.lain-editor.display :as display]
            [graph.lain-editor.runtime :as editor]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.one-time-network :as once]
            [propagators.compiler-2.runtime.operators.network-observation :as observation]
            [propagators.compiler-2.runtime.operators.network-traversal :as traversal]
            [propagators.compiler-2.runtime.session.input :as input]
            [propagators.datastructures.layered-value :as datum]
            [propagators.datastructures.membership-collection :as membership]
            [propagators.experimental.visualization.data :as data]
            [propagators.gur :as gur]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as builder]
            [propagators.observer :as observers]
            [propagators.runner :as runner]))

(def imports
  "(load-lain \"propagators/compiler_2/runtime/lain/semantic_traversal.lain\" 0)")

(def program
  "(define right 4)
   (define input 3)
   (define other 9)
   (define unrelated (* other 3))
   (def-cells result)
   (-> (+ input right) result)")

(defn- prepare [source]
  (let [steps (atom 0)]
    (binding [runner/*advance-transform*
              (fn [advance]
                (fn [state continuations]
                  (when (> (swap! steps inc) 15000)
                    (throw (ex-info "Observation failed to reach quiescence" {})))
                  (advance state continuations)))]
      (editor/prepare (str imports source) []))))

(defn- graph [pane]
  (data/payload (net/network-cell-strongest (:program/net @(:session pane)) display/cell-id)))

(defn- labels [graph] (set (map str (vals (:nodes graph)))))

(defn- shape [graph]
  {:nodes (frequencies (map (fn [[id label]] [(get (:node-kinds graph) id) (str label)]) (:nodes graph)))
   :edges (set (map (fn [[a b]] [(str (get (:nodes graph) a)) (str (get (:nodes graph) b))]) (:edges graph)))})

(defn- binding-id [pane name]
  (let [state @(:session pane)]
    (env/resolve-binding-id (:program/net state) (:program/env state) name)))

(deftest semantic-snapshot-implements-the-neighbor-api-and-stays-frozen
  (let [pane (prepare (str program "(display result)"))]
    (try
      (let [network (:program/net @(:session pane))
            root (binding-id pane 'input)
            frozen (observation/frozen-snapshot network)
            semantic (traversal/semantic-snapshot frozen root)
            neighbors (observation/snapshot-neighbors semantic root)
            op (first (:node/outputs neighbors))]
        (is (= [[:outer] root] (:node/id neighbors)))
        (is (= :propagator (:node/kind (observation/snapshot-node-info semantic op))))
        (is (= '+ (:node/name (observation/snapshot-node-info semantic op))))
        (is (= "input" (str (:node/name (observation/snapshot-node-info semantic root)))))
        (is (= (observation/snapshot-semantic-graph frozen root)
               (:graph (traversal/semantic-snapshot frozen root))))
        (let [updated (builder/seed-cell network root 99)
              changed (traversal/semantic-snapshot (observation/frozen-snapshot updated) root)]
          (is (= 3 (get-in semantic [:graph :values [[:outer] root]])))
          (is (= 99 (get-in changed [:graph :values [[:outer] root]])))
          (is (= (:graph semantic) (:graph (traversal/semantic-snapshot frozen root)))))
        (is (thrown? Exception (traversal/semantic-snapshot frozen (ids/new-node-id))))
        (is (identical? network (:program/net @(:session pane)))))
      (finally (editor/close-session! (:session pane))))))

(deftest downstream-preserves-call-ports-and-excludes-unrelated-branches
  (let [pane (prepare (str program "(display (observe-dependents input))"))]
    (try
      (let [g (graph pane) calls (filter #(= :propagator (get (:node-kinds g) %)) (keys (:nodes g)))
            pairs (set (map (fn [[a b]] [(str (get (:nodes g) a)) (str (get (:nodes g) b))]) (:edges g)))]
        (is (= :downstream (:traversal/direction g)))
        (is (every? (labels g) ["input" "right" "+" "result"]))
        (is (not-any? (labels g) ["other" "unrelated" "*" "observe-dependents" "observe-semantic"]))
        (is (contains? pairs ["right" "+"]))
        (is (= #{'-> '+} (set (map (:nodes g) calls))))
        (is (= :network (:dataflow/representation g)))
        (is (= :leapfrog (:dataflow/projection g)))
        (is (not (contains? g :dataflow/observer-applications))))
      (finally (editor/close-session! (:session pane))))))

(deftest observation-composes-through-arbitrarily-named-lain-wrappers
  (let [direct (prepare (str program "(display (observe-dependents input))"))
        wrapped (prepare (str program
                        "(define arbitrary (network (x) (observe-dependents x)))
                         (define another (network (x) (arbitrary x)))
                         (display (another input))"))]
    (try
      (is (= (shape (graph direct)) (shape (graph wrapped))))
      (is (not-any? (labels (graph wrapped)) ["arbitrary" "another"]))
      (let [network (:program/net @(:session direct))
            observer-id (first (keys (observers/observers network)))]
        (is (= (shape (graph direct)) (shape (observers/sample-observer network observer-id)))))
      (finally
        (editor/close-session! (:session direct))
        (editor/close-session! (:session wrapped))))))

(deftest repeated-sampling-installs-no-callback-topology-into-parent
  (let [pane (prepare (str program "(display (observe-dependents input))"))]
    (try
      (let [network (:program/net @(:session pane))
            registered (observers/observers network)
            sample-id (first (keys registered))
            before-env (net/net-env network) before-graph (net/net-graph network)]
        (is (= 1 (count registered)))
        (let [output (:source (first (datum/support-of
                                      (net/network-cell-strongest network display/cell-id))))]
          (doseq [private [(gur/stable-node-id [:propagators.compiler-2.runtime.operators.network-sampling/root output])
                           (gur/stable-node-id [:compiler-2 :one-time-network output :snapshot])
                           (gur/stable-node-id [:compiler-2 :one-time-network output :result])]]
            (is (not (contains? before-env private)))))
        (doseq [_ (range 3)]
          (is (= (shape (graph pane)) (shape (observers/sample-observer network sample-id)))))
        (is (identical? before-env (net/net-env network)))
        (is (identical? before-graph (net/net-graph network)))
        (is (identical? network (:program/net @(:session pane)))))
      (finally (editor/close-session! (:session pane))))))

(deftest pending-roots-late-dependents-and-supported-values-refresh
  (let [editors (atom {})]
    (try
      (editor/run! editors {:pane "a" :source (str imports
        "(def-cells input result) (-> (+ input 1) result)
         (display (observe-dependents input))")})
      (let [pane (get @editors "a") session (:session pane) root (binding-id pane 'input)]
        (is (contains? (labels (graph pane)) "+"))
        (let [size (count (net/net-env (:program/net @session)))]
         (doseq [[version base expected] [[1 7 8] [2 9 10]]]
          (input/commit-runtime-input!
           session {:runtime/input :cell-message :cell-id root
                    :update (membership/content
                             {:base base :support #{{:source root :timestamp version :premises-status :active}}
                              :membership #{}})})
          (let [g (graph pane) result-id (first (for [[id label] (:nodes g) :when (= "result" (str label))] id))
                result (get (:values g) result-id)]
            (is (= expected (data/payload result)))
            (is (= #{root} (set (map :source (datum/support-of result)))))
            (is (= #{version} (set (map :timestamp (datum/support-of result))))))
          (is (= size (count (net/net-env (:program/net @session)))))))
        (editor/extend! editors {:pane "a" :generation (:generation pane)
                                :source "(def-cells doubled) (-> (* result 2) doubled)"})
        (is (every? (labels (graph (get @editors "a"))) ["*" "doubled"])))
      (finally (editor/close! editors)))))

(deftest a-co-input-update-refreshes-the-observation
  (let [pane (prepare
              "(define input 3) (def-cells right result)
               (-> (+ input right) result)
               (display (observe-dependents input))")]
    (try
      (let [root (binding-id pane 'right)]
        (doseq [[version base expected] [[1 4 7] [2 6 9]]]
          (input/commit-runtime-input!
           (:session pane) {:runtime/input :cell-message :cell-id root
                            :update (membership/content
                                     {:base base :support #{{:source root :timestamp version :premises-status :active}}
                                      :membership #{}})})
          (let [g (graph pane)
                result-id (first (for [[id label] (:nodes g) :when (= "result" (str label))] id))]
            (is (= expected (data/payload (get (:values g) result-id)))))))
      (finally (editor/close-session! (:session pane))))))

(deftest flag-dependents-include-branch-values-without-their-producers
  (let [pane (prepare
              "(define flag true) (define x 7)
               (define result (if flag (+ x 1) (* x 2)))
               (display (observe-dependents flag))")]
    (try
      (let [g (graph pane)
            calls (set (for [[id label] (:nodes g) :when (= :propagator (get (:node-kinds g) id))]
                         (str label)))]
        (is (= #{"if"} calls))
        (is (contains? (labels g) "flag"))
        (is (not (contains? (labels g) "x"))))
      (finally (editor/close-session! (:session pane))))))

(deftest both-directions-retain-the-callable-producing-chain
  (let [pane (prepare
              "(define factory (network (bias) (network (x) (+ x bias))))
               (define add-ten (factory 10))
               (define input 5) (define result (add-ten input))
               (display (observe-semantic input :both))")]
    (try
      (is (every? (labels (graph pane)) ["factory" "add-ten" "input" "result"]))
      (is (not (contains? (labels (graph pane)) "+")))
      (is (= :both (:traversal/direction (graph pane))))
      (finally (editor/close-session! (:session pane))))))

(deftest generic-sampling-accepts-ordinary-lain-callbacks-and-arguments
  (let [pane (prepare
              "(define input 3)
               (define sample-number (network (snapshot root offset) (+ offset 1)))
               (display (observe-network input sample-number 41))")]
    (try
      (is (= 42 (graph pane)))
      (is (= 1 (count (observers/observers (:program/net @(:session pane))))))
      (finally (editor/close-session! (:session pane)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callback must be a network"
                       (prepare "(define input 3) (display (observe-network input 7))"))))

(deftest existing-lain-breadth-first-walker-consumes-semantic-neighbors
  (let [pane (prepare
              "(load-lain \"propagators/compiler_2/runtime/lain/network_trace.lain\")
               (define input false)
               (define result (not input))
               (display result)")]
    (try
      (let [network (:program/net @(:session pane))
            root (binding-id pane 'input)
            semantic (traversal/semantic-snapshot (observation/frozen-snapshot network) root)
            [context root-argument direction output] (repeatedly 4 ids/new-node-id)
            private (-> network
                        (builder/install-cell context :context :context)
                        (builder/install-cell root-argument [[:outer] root] [[:outer] root])
                        (builder/install-cell direction :downstream :downstream))
            result (once/run-flat-gur-once private context (binding-id pane 'trace-breadth-first)
                                           [root-argument direction] (once/compound-projection)
                                           output semantic)]
        (is (= [[:outer] root] (get-in result [:value :trace/root])))
        (is (= 3 (count (get-in result [:value :trace/nodes]))))
        (is (not (contains? (net/net-env network) output))))
      (finally (editor/close-session! (:session pane))))))

(deftest compound-calls-stay-at-the-root-abstraction
  (let [pane (prepare
              "(define inner (network (x) (+ x 1)))
               (define pipeline (network (x) (inner (* x 2))))
               (define input 3) (define result (pipeline input))
               (display (observe-dependents input))")]
    (try
      (is (contains? (labels (graph pane)) "pipeline"))
      (is (not-any? (labels (graph pane)) ["inner" "+" "*"]))
      (finally (editor/close-session! (:session pane))))))

(deftest traversal-rejects-invalid-directions-and-snapshots
  (is (thrown? Exception (traversal/semantic-snapshot :invalid (ids/new-node-id))))
  (is (thrown? Exception (traversal/traverse :invalid (ids/new-node-id) :both)))
  (is (thrown? Exception (traversal/traverse (traversal/->SemanticGraphSnapshot {}) :root :invalid))))
