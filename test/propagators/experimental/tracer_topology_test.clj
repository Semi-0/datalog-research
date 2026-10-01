(ns propagators.experimental.tracer-topology-test
  "Exact high-level topology oracles from Lain declarations, not projected edges."
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.runtime.session.file-loader :as loader]
            [propagators.experimental.ttms-primitives :as ttms]
            [propagators.experimental.visualization.data :as data]
            [propagators.experimental.visualization.extension :as extension]
            [propagators.gur.flat :as gur]
            [propagators.helpers.task-queue :as tq]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.runner :as runner]))

(def environments
  {:legacy {:extensions [extension/extension]}
   :ttms {:extensions [extension/ttms-extension ttms/session-extension]}})

(def scenarios
  [{:file "chain" :seeds {'a 2 'b 3} :result ['result 15]
    :applications [['+ ['a 'b] ['a 'b] ['middle]]
                                 ['* ['middle 'b] ['middle 'b] ['result]]]}
   {:file "diamond" :seeds {'a 2 'b 3} :result ['result 11]
    :applications [['+ ['a 'b] ['a 'b] ['left]]
                                   ['* ['a 'b] ['a 'b] ['right]]
                                   ['+ ['left 'right] ['left 'right] ['result]]]}
   {:file "repeated" :seeds {'a 2} :result ['result 4]
    :applications [['step ['a 'middle] ['a] ['middle]]
                                    ['step ['middle 'result] ['middle] ['result]]]}
   {:file "nested" :seeds {'a 2} :result ['result 4]
    :applications [['pair ['a 'result] ['a] ['result]]]}
   {:file "cycle" :seeds {'a 2} :result ['c 2] :edges [['a 'b] ['b 'c] ['c 'a]]}
   {:file "bidirectional" :seeds {'c 2} :result ['a 2]
    :edges [['a 'b] ['b 'a] ['b 'c] ['c 'b]]}
   {:file "conditional" :seeds {'condition true 'a 10 'b 20} :result ['result 10]
    :applications [['if ['condition 'a 'b] ['condition 'a 'b] ['result]]]}])

(defn binding-id [state symbol]
  (env/resolve-binding-id (:program/net state) (:program/env state) symbol))

(defn node-key [state symbol] [[:outer] (binding-id state symbol)])

(defn expected-application-edges [state [operator arguments inputs outputs]]
  (let [argument-ids (mapv #(binding-id state %) arguments)
        operator-id (binding-id state operator)
        matches (filter #(and (= operator-id (:operator-id %))
                              (= argument-ids (:argument-ids %)))
                        (application/application-topologies (:program/net state)))]
    (is (= 1 (count matches)) (str "Unique declared occurrence: " operator arguments))
    (let [id [[:outer] (gur/stable-node-id [(:application-id (first matches)) :apply-prop])]]
      (concat (map #(vector (node-key state %) id) inputs)
              (map #(vector id (node-key state %)) outputs)))))

(defn graph-value [state]
  (data/payload (net/network-cell-strongest (:program/net state)
                                          (binding-id state 'semantic))))

(defn inject [state seeds]
  (reduce (fn [state [symbol value]]
            (let [[network tasks] (nb/seed-cell! (:program/net state) tq/empty-queue
                                                (binding-id state symbol) value)]
              (assoc state :program/net
                     (runner/completed-network (runner/run-network tasks network)))))
          state seeds))

(defn assert-graph [graph expected applications]
  (is (= expected (set (:edges graph))) "No missing, spurious, or reversed edges")
  (is (= (set (mapcat identity expected)) (set (keys (:nodes graph))))
      "No leaked compound internals")
  (is (= (count applications) (count (filter #{:propagator} (vals (:node-kinds graph)))))))

(defn assert-live-result [state seeds [symbol expected-value] expected applications]
  (let [settled (inject state seeds)
        repeated (inject settled seeds)
        graph (graph-value settled)
        node (node-key settled symbol)]
    (assert-graph graph expected applications)
    (is (= expected-value (data/payload
                           (net/network-cell-strongest (:program/net settled) (second node))))
        "Lain computation actually produces the expected value")
    (is (= expected-value (data/payload (get (:values graph) node)))
        "Published graph samples the current result")
    (is (= graph (graph-value repeated)) "Duplicate injection does not alter the graph")
    (is (= (set (keys (net/net-env (:program/net settled))))
           (set (keys (net/net-env (:program/net repeated))))) "No repeat topology growth")))

(deftest exact-high-level-topology-across-lain-programs
  (doseq [[mode options] environments
          {:keys [file applications edges seeds result]} scenarios]
    (testing (str mode " / " file)
      (let [state @(loader/load-session-from-file
                    (str "examples/lain/visualization_combinators/topology_scenarios/"
                         file ".lain") options)
            graph (graph-value state)
            expected (set (concat (mapcat #(expected-application-edges state %) applications)
                                  (map #(mapv (partial node-key state) %) edges)))]
        (assert-graph graph expected applications)
        (assert-live-result state seeds result expected applications)
        (is (= (count (:edges graph)) (count (set (:edges graph)))))
        (println "TOPOLOGY" mode file "nodes" (count (:nodes graph))
                 "edges" (count (:edges graph)))))))

(deftest late-callable-refreshes-topology-without-an-input-write
  (doseq [[mode options] environments]
    (testing (str mode " / late")
      (let [state @(loader/load-session-from-file
                    "examples/lain/visualization_combinators/topology_scenarios/late.lain"
                    options)
            callable (net/network-cell-strongest (:program/net state) (binding-id state 'identity))
            arrived (inject state {'later callable})
            declaration ['later ['a 'result] ['a] ['result]]
            expected (set (expected-application-edges arrived declaration))]
        (is (empty? (:edges (graph-value state))) "Pending callable has no invented ports")
        (assert-graph (graph-value arrived) expected [declaration])
        (assert-live-result arrived {'a 7} ['result 7] expected [declaration])))))

(deftest replacing-the-lain-program-replaces-topology
  (doseq [[mode options] environments]
    (testing (str mode " / chain-to-diamond reload")
      (let [directory "examples/lain/visualization_combinators/topology_scenarios/"
            session (loader/load-session-from-file (str directory "chain.lain") options)
            before (graph-value @session)
            {:keys [applications seeds result]} (second scenarios)]
        (is (= 6 (count (:edges before))))
        (loader/replace-session-from-source! session (slurp (str directory "diamond.lain")) options)
        (let [state @session
              expected (set (mapcat #(expected-application-edges state %) applications))]
          (assert-graph (graph-value state) expected applications)
          (is (= 9 (count (:edges (graph-value state)))))
          (assert-live-result state seeds result expected applications))))))
