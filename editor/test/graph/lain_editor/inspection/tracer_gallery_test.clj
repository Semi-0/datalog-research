(ns graph.lain-editor.inspection.tracer-gallery-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [graph.json :as json]
            [graph.lain-editor.runtime.display :as display]
            [graph.lain-editor.inspection.core :as inspection]
            [graph.lain-editor.runtime.session :as runtime]
            [graph.lain-editor.workspace.model :as workspace]
            [propagators.compiler-2.runtime.session.clock :as clock]
            [propagators.compiler-2.model.env :as env]
            [propagators.network :as net]))

(def directory "examples/lain/tracer_gallery/")

(def required-surface
  {"01-current-session" ["(trace:session result :both traced)"]
   "02-semantic" ["trace:semantic"]
   "03-roots-dataflow" ["trace:roots" "trace:dataflow" "trace:with-values"]
   "04-roots-semantic" ["trace:root-semantic"]
   "05-children" ["trace:children"]
   "06-downstream" ["trace:downstream-retained"]
   "07-both" ["trace:observe-retained" ":both"]
   "08-caller-view" ["trace:caller"]
   "09-replay" ["trace:history"]
   "10-live-observer" ["trace:observe-live" ":upstream"]
   "11-clock-history" ["clock-in 500" "trace:history"]
   "12-call-graph" ["trace:calls pipeline" "pipeline 4"
                    "Datalog derives calls/called-by"]})

(defn- inspect-pane [editors pane]
  (inspection/inspect
   editors
   {:pane (:id pane)
    :generation (:generation (get @editors (:id pane)))
    :display (:display pane)}))

(defn- graph-contract [expression]
  (let [editors (atom {})
        source (str "(load-lain \"propagators/compiler_2/runtime/lain/tracers.lain\")\n"
                    "(define combine (network [a b] (+ (* a 2) b)))\n"
                    "(define left 3) (define right 4) (define result (combine left right))\n"
                    "(display " expression ")")]
    (try
      (runtime/run! editors {:pane "contract" :source source})
      (let [view (inspect-pane editors {:id "contract"
                                        :display {:cell "@display" :mode "graph" :direction "both"}})]
        (is (= "ready" (:status view)))
        ;; Session capabilities and generated copy IDs differ between runs.
        ;; Compare node/value multiplicities and their directed connectivity.
        (let [graph (:graph view)
              signatures (into {} (map (fn [node]
                                         [(:id node) (dissoc node :id :semanticScope :referenceTokens)])
                                       (:nodes graph)))]
          (-> (select-keys graph [:scope :representation])
              (assoc :nodes (frequencies (vals signatures))
                     :edges (frequencies
                             (map (fn [edge]
                                    (-> edge (update :from signatures) (update :to signatures)))
                                  (:edges graph)))))))
      (finally (runtime/close! editors)))))

(deftest named-tracers-preserve-the-original-relationship-graphs
  (doseq [[original named]
          [["(relationship:with-values (relationship:semantic left))" "(trace:semantic left)"]
           ["(relationship:with-values (relationship:dataflow (relationship:roots left)))" "(trace:with-values (trace:dataflow (trace:roots left)))"]
           ["(relationship:with-values (relationship:roots-semantic left))" "(trace:root-semantic left)"]
           ["(relationship:with-values (relationship:children result))" "(trace:children result)"]
           ["(relationship:caller-view (relationship:with-values (relationship:semantic left)))" "(trace:caller left)"]
           ["(call-graph combine)" "(trace:calls combine)"]
           ["(trace:observe-retained result :upstream)" "(trace:upstream result)"]
           ["(trace:downstream-retained left)" "(trace:downstream left)"]]]
    (testing named
      (is (= (graph-contract original) (graph-contract named))))))

(deftest directional-tracers-inspect-pending-compound-calls
  (doseq [[tracer root producer?]
          [["trace:upstream" "consumed" true]
           ["trace:downstream" "queue" true]
           ["trace:downstream" "consumed" false]
           ["trace:upstream" "queue" false]]]
    (testing (str tracer " " root)
      (let [editors (atom {})
            source (str "(load-lain \"propagators/compiler_2/runtime/lain/tracers.lain\")\n"
                        "(define queue)\n"
                        "(define consumed (consume-task agents queue))\n"
                        "(define consume-task (network [agents queue] (+ queue 1)))\n"
                        "(display (" tracer " " root "))")]
        (try
          (let [{:keys [generation]} (runtime/run! editors {:pane "pending" :source source})
                request {:pane "pending" :generation generation
                         :display {:cell "@display" :mode "graph" :direction "both"}}
                view (inspection/inspect editors request)
                graph (:graph view)
                call (first (filter #(= "consume-task" (:label %)) (:nodes graph)))]
            (is (= "ready" (:status view)))
            (is (some? (:semanticObservation graph)) "Directional tracers retain their observation")
            (is (= (if producer? #{"consume-task" "agents" "queue" "consumed"} #{root})
                   (set (map :label (:nodes graph)))))
            (is (= producer? (some? call)))
            (when producer?
              (let [child (:graph (inspection/inspect editors (assoc request :parent (:id call))))]
                (is (some #(= "+" (:label %)) (:nodes child))
                    "A compound body remains inspectable before its inputs arrive")
                (is (= "the-nothing"
                       (:value (first (filter #(= "consumed" (:label %)) (:nodes graph)))))))))
          (finally (runtime/close! editors)))))))

(deftest old-imports-retain-their-public-bindings
  (let [candidate (runtime/prepare
                   "(load-lain \"examples/lain/network_replay/collect-history.lain\")" [])
        state @(:session candidate)
        network (:program/net state)
        read-binding (fn [name]
                       (let [id (env/resolve-binding-id network (:program/env state) name)]
                         (is (some? id))
                         (display/payload (net/network-cell-strongest network id))))]
    (try
      (doseq [[old public] [['semantic-neighborhood 'trace:semantic-neighborhood]
                            ['observe-semantic 'trace:observe-live]
                            ['observe-dependents 'trace:downstream-live]
                            ['observe-semantic-retained 'trace:observe-retained]
                            ['observe-dependents-retained 'trace:downstream-retained]
                            ['trace:downstream 'trace:downstream-retained]
                            ['collect-frame 'trace:append-frame]
                            ['collect-history 'trace:graph-history]
                            ['collect-semantic-history 'trace:history]]]
        (is (= (read-binding old) (read-binding public))))
      (finally (runtime/close-session! (:session candidate))))))

(deftest workspace-covers-and-runs-the-public-tracer-surfaces
  (let [document (json/read-json (slurp (str directory "workspace.json")))
        files (into #{}
                    (comp (filter #(.isFile %))
                          (map #(.getName %))
                          (filter #(str/ends-with? % ".lain")))
                    (.listFiles (io/file directory)))
        editors (atom {})]
    (is (= document (workspace/validate document)))
    (is (= (set (keys required-surface)) (set (map :id (:panes document)))))
    (is (= files (set (map :filename (:panes document)))))
    (doseq [{:keys [id filename source executedSource]} (:panes document)]
      (testing id
        (is (= source executedSource (slurp (str directory filename))))
        (is (str/includes? source "propagators/compiler_2/runtime/lain/tracers.lain"))
        (doseq [required (get required-surface id)]
          (is (str/includes? source required)))))
    (try
      (is (= (set (keys required-surface))
             (set (keys (:generations (runtime/restore! editors document))))))
      (let [pane (get @editors "11-clock-history")
            session (:session pane)
            subscription-id (first (keys (:clock/subscriptions @session)))
            before (count (get-in (inspect-pane editors
                                                (some #(when (= "11-clock-history" (:id %)) %)
                                                      (:panes document)))
                                  [:replay :frames]))]
        (clock/stop-all! session)
        (is subscription-id)
        (clock/tick-clock! session subscription-id (constantly 4242))
        (is (< before
               (count (get-in (inspect-pane editors
                                            (some #(when (= "11-clock-history" (:id %)) %)
                                                  (:panes document)))
                              [:replay :frames])))))
      (doseq [pane (:panes document)]
        (testing (:id pane)
          (let [result (inspect-pane editors pane)]
            (is (= "ready" (:status result)))
            (if (contains? #{"09-replay" "11-clock-history"} (:id pane))
              (is (seq (get-in result [:replay :frames])))
              (is (seq (get-in result [:graph :nodes]))))
            (when (= "01-current-session" (:id pane))
              (is (= 1 (count (:trace/subscriptions
                               @(:session (get @editors (:id pane)))))))
              (is (some #(= "+" (:label %)) (get-in result [:graph :nodes]))))
            (when (= "05-children" (:id pane))
              (is (= "child" (get-in result [:graph :scope :kind]))))
            (when (= "12-call-graph" (:id pane))
              (let [network (:program/net
                             @(:session (get @editors (:id pane))))
                    graph (display/payload
                           (net/network-cell-strongest network display/cell-id))
                    statuses (set (keep :call/status (vals (:values graph))))]
                (is (= "callgraph" (get-in result [:graph :representation])))
                (is (= #{:potential :realized} statuses))
                (is (seq (get-in graph [:graph/relations :calls])))
                (is (seq (get-in graph [:graph/relations :called-by])))
                (is (some (fn [[_ value]]
                            (and (:call/from value) (:call/to value)
                                 (:call/called-by value)))
                          (:values graph)))
                (is (some #(= "central environment" (:label %))
                          (get-in result [:graph :nodes])))
                (is (some #(= "captures" (:label %))
                          (get-in result [:graph :nodes])))
                (is (some #(= "extends" (:label %))
                          (get-in result [:graph :nodes])))
                (is (some #(= "call frame" (:label %))
                          (get-in result [:graph :nodes])))
                (is (some #(= "shift" (:label %))
                          (get-in result [:graph :nodes])))
                (is (some #(= "square" (:label %))
                          (get-in result [:graph :nodes]))))))))
      (finally
        (runtime/close! editors)))))
