(ns propagators.relationship-dataflow-test
  (:require [clojure.test :refer [deftest is]]
            [graph.xr-runtime :as xr]
            [propagators.cells.value :as value]
            [propagators.experimental.visualization.data :as data]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.extensions.relationship-xr :as extension]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.relationship-dataflow :as dataflow]
            [propagators.runner :as runner]
            [propagators.semantic-trace :as trace]))

(def options {:extensions [(extension/extension)]})

(defn graph-value [state name]
  (let [network (:program/net state)]
    (data/payload (net/network-cell-strongest
                   network (env/resolve-binding-id network (:program/env state) name)))))

(defn labeled-edges [graph]
  (set (map #(mapv (:nodes graph) %) (:edges graph))))

(deftest chain-projects-exact-semantic-connectivity
  (let [state @(loader/load-session-from-file
                "examples/lain/relationship-chain.lain" options)
        raw (graph-value state 'relationships)
        graph (graph-value state 'semantic)]
    (is (= 8 (count (:nodes graph))))
    (is (< (count (:nodes graph)) (count (:nodes raw))))
    (is (= #{["CONSTANT" 'source] ['source "CONSTANT"] ['source '+] ["CONSTANT" '+]
             ['+ 'middle] ['middle '*] ["CONSTANT" '*] ['* 'result]}
           (labeled-edges graph)))
    (is (= 8 (count (:edges graph))))
    (is (= {:cell 6 :propagator 2} (frequencies (vals (:node-kinds graph)))))
    (is (= 2 (count (filter (fn [[id label]]
                             (and (= "CONSTANT" label)
                                  (= 1 (data/payload (get (:values graph) id)))))
                           (:nodes graph)))))
    (is (= 4 (get (:values graph)
                  (first (keep (fn [[id label]] (when (= 'result label) id))
                               (:nodes graph))))))
    (is (= 8 (count (:nodes (xr/graph->json graph)))))))

(deftest explicit-node-kinds-survive-tracing-and-override-label-guesses
  (let [graph (trace/graph-union
               {:nodes {:a '+ :b 'custom-transformation}
                :node-kinds {:a :cell :b :propagator}
                :edges [[:a :b]]})
        traced (trace/trace-graph graph {:node :b :direction :upstream})
        json (xr/graph->json traced)]
    (is (= (:node-kinds graph) (:node-kinds traced)))
    (is (= {"+" "cell" "custom-transformation" "propagator"}
           (into {} (map (juxt :label :kind)) (:nodes json))))))

(deftest fan-out-retains-expression-result
  (let [state @(loader/load-session-from-source
                "(define a) (define b) (define c) (define raw) (define semantic)
                 (<-> 1 a)
                 (let-cell [v] (-> (+ a 1) v) (-> v b) (-> v c))
                 (relationship:roots a raw)
                 (relationship:dataflow raw semantic)" options)
        edges (labeled-edges (graph-value state 'semantic))]
    (is (contains? edges ['v 'b]))
    (is (contains? edges ['v 'c]))
    (is (contains? edges ['+ 'v]))))

(deftest projection-follows-cell-updates-and-environment-replacement
  (let [source "(define a) (define b) (define raw) (define semantic)
                (-> (+ a 1) b)
                (relationship:roots a raw)
                (relationship:dataflow raw semantic)"
        session (loader/load-session-from-source source options)
        state @session
        network (:program/net state)
        a (env/resolve-binding-id network (:program/env state) 'a)
        [updated tasks] (nb/seed-cell! network tq/empty-queue a 5)
        settled (runner/completed-network (runner/run-network tasks updated))
        graph (graph-value (assoc state :program/net settled) 'semantic)
        b (first (keep (fn [[id label]] (when (= 'b label) id)) (:nodes graph)))]
    (is (= 6 (get (:values graph) b)))
    (loader/replace-session-from-source!
     session (str source " (<-> 9 a)") options)
    (let [fresh (graph-value @session 'semantic)
          b (first (keep (fn [[id label]] (when (= 'b label) id)) (:nodes fresh)))]
      (is (= 10 (get (:values fresh) b))))))

(deftest invalid-and-empty-input
  (is (thrown? clojure.lang.ExceptionInfo
               (dataflow/dataflow-graph net/empty-net 42)))
  (is (empty? (:nodes (dataflow/dataflow-graph
                       net/empty-net {:semantic-trace/graph true :nodes {}})))))

(def empty-graph (trace/graph-union {:nodes {} :edges []}))

(defn projection-fixture [input]
  (let [[in out healthy] (repeatedly 3 ids/new-node-id)
        network (nb/seed-cell (nb/install-cells [in out healthy]) in input)
        [projector network] ((dataflow/p:dataflow in out) network)
        [other network] ((prop/construct-propagator
                          :healthy (fn [_ _ _] [(message healthy 7)]) [] [healthy])
                         network)]
    {:network network :in in :out out :healthy healthy
     :projector projector :other other}))

(defn activate-projector [{:keys [network projector]}]
  ((prop/prop-f (net/network-env-lookup network projector)) [] [] network))

(deftest concrete-guard-and-invalid-values
  (doseq [input [value/nothing value/contradiction]]
    (with-redefs [dataflow/dataflow-graph (fn [& _] (throw (AssertionError. "guard failed")))]
      (is (= [] (activate-projector (projection-fixture input))))))
  (doseq [input [false 42]]
    (let [{:keys [in out] :as fixture} (projection-fixture input)
          result (:value (first (activate-projector fixture)))
          diagnostic (first (value/contradiction-provenance result))]
      (is (value/contradiction? result))
      (is (= {:operation :relationship/dataflow :input-cell in :output-id out
              :reason "relationship:dataflow expects a relationship graph"
              :exception-class "clojure.lang.ExceptionInfo"}
             diagnostic)))))

(deftest errors-merge-with-reasons-and-do-not-abort-other-work
  (doseq [previous [value/nothing empty-graph]]
    (let [{:keys [network in out healthy projector other]}
          (projection-fixture false)
          network (nb/seed-cell network out previous)
          result (runner/run-network [projector other] network)
          settled (runner/completed-network result)
          output (net/network-cell-strongest settled out)]
      (is (= :completed (:status result)))
      (is (= 7 (net/network-cell-strongest settled healthy)))
      (is (value/contradiction? output))
      (is (some #(and (= in (:input-cell %))
                      (= :relationship/dataflow (:operation %))
                      (= "relationship:dataflow expects a relationship graph" (:reason %)))
                (value/contradiction-provenance output))))))

(deftest application-edge-errors-are-inside-the-projection-boundary
  (let [state @(loader/load-session-from-file
                "examples/lain/relationship-chain.lain" options)
        network (:program/net state)
        [input output] (repeatedly 2 ids/new-node-id)
        network (-> network (nb/ensure-cell input) (nb/ensure-cell output)
                    (nb/seed-cell input (graph-value state 'relationships)))
        [projector network] ((dataflow/p:dataflow input output) network)]
    (with-redefs [dataflow/application-edges
                  (fn [_] (throw (ex-info "edge projection failed" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"edge projection failed"
                            (dataflow/dataflow-graph network (graph-value state 'relationships))))
      (let [settled (runner/completed-network (runner/run-network [projector] network))
            result (net/network-cell-strongest settled output)]
        (is (value/contradiction? result))
        (is (= #{"edge projection failed"}
               (set (map :reason (value/contradiction-provenance result)))))))))

(deftest cancellation-interruption-and-jvm-errors-remain-exceptional
  (doseq [error [(java.util.concurrent.CancellationException. "cancelled")
                 (AssertionError. "fatal")]]
    (is (identical? error
          (try (dataflow/project-result (fn [& _] (throw error)) nil nil nil nil)
               (catch Throwable caught caught)))))
  (try
    (let [result (try
                   (dataflow/project-result
                    (fn [& _] (throw (InterruptedException. "interrupted"))) nil nil nil nil)
                   (catch InterruptedException error
                     {:error error :interrupted? (.isInterrupted (Thread/currentThread))}))]
      (Thread/interrupted)
      (is (instance? InterruptedException (:error result)))
      (is (:interrupted? result)))
    (finally (Thread/interrupted))))

(deftest if-selected-operator-is-a-semantic-input-to-the-dynamic-call
  (doseq [[flag expected] [["true" 5] ["false" 8]]]
    (let [source (str "(define flag " flag ") (define input 4) (define result)
                       (-> ((if flag (network [x] (+ x 1))
                                     (network [x] (* x 2))) input) result)
                       (define raw) (define semantic)
                       (relationship:roots input raw)
                       (relationship:dataflow raw semantic)")
          session (loader/load-session-from-source source options)
          graph (graph-value @session 'semantic)
          operation (fn [label] (first (for [[id name] (:nodes graph)
                                            :when (and (= label name)
                                                       (= :propagator (get-in graph [:node-kinds id])))] id)))
          chooser (operation 'if) call (operation :propagator/anonymous)
          selected (set (for [[from to] (:edges graph) :when (= chooser from)] to))
          used (set (for [[from to] (:edges graph) :when (= call to)] from))]
      (is (= expected (graph-value @session 'result)))
      (is (some? chooser)) (is (some? call))
      (is (some selected used) "The chosen network must feed the dynamic call"))))

(deftest returned-member-values-reach-their-caller-cells
  (let [source "(define stats (network [x y sum product]
                  (-> (+ x y) sum) (-> (* x y) product) (list sum product)))
                (define sum) (define product) (define result)
                (stats 3 4 sum product) (-> (+ sum product) result)
                (define raw) (define semantic)
                (relationship:roots sum raw) (relationship:dataflow raw semantic)"
        session (loader/load-session-from-source source options)
        graph (graph-value @session 'semantic)
        network (:program/net @session)]
    (is (= 19 (graph-value @session 'result)))
    (doseq [[name expected] [['sum 7] ['product 12]]]
      (let [caller [[:outer] (env/resolve-binding-id network (:program/env @session) name)]
            sources (for [[source target] (:edges graph)
                          :when (and (= caller target) (= name (get (:nodes graph) source)))] source)]
        (is (= 1 (count sources)))
        (is (not= caller (first sources)))
        (is (= expected (get (:values graph) (first sources))))))))

(deftest return-copy-flow-preserves-origins-and-terminates-on-cycles
  (let [flow (dataflow/return-flow {:result #{:port} :port #{:acc}} :result)]
    (is (= #{:acc} (:sources flow)))
    (is (= #{[:port :result] [:acc :port]} (set (:edges flow)))))
  (let [flow (dataflow/return-flow {:result #{:left :right}} :result)]
    (is (= #{:left :right} (:sources flow)))
    (is (= #{[:left :result] [:right :result]} (set (:edges flow)))))
  (let [flow (dataflow/return-flow {:a #{:b} :b #{:a}} :a)]
    (is (empty? (:sources flow)))
    (is (= #{[:a :b] [:b :a]} (set (:edges flow))))))
