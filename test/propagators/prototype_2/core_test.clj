(ns propagators.prototype-2.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.prototype-2.core :as p2]))

(deftest id-chain-of-five-propagates-random-value
  (testing "five identity propagators carry one random seed to the end cell"
    (let [cells [:c0 :c1 :c2 :c3 :c4 :c5]
          seed (rand-int 10000)
          network (p2/install-id-chain p2/empty-network cells)
          [tasks network] (p2/tell network :c0 seed)
          network (p2/run-tasks network tasks)]
      (is (= seed (p2/cell-value network :c0)))
      (is (= seed (p2/cell-value network :c5)))
      (doseq [c cells]
        (is (= seed (p2/cell-value network c))
            (str "cell " c " should hold the seed")))
      (is (= #{:c0} (p2/first-dependences network :c5)))
      (is (p2/chain-complete? network :c5))
      (is (p2/chain-complete? network (p2/edge-chain #{:c0} #{:c5})))
      ;; each built node inherits origins #{:c0}; tail is its downstream cell
      (let [dep (p2/dependence-of network :c5)]
        (is (= #{:c0} (p2/edge-chain-origins dep)))
        (is (= #{:c5} (p2/edge-chain-tail dep))))
      (doseq [c [:c1 :c2 :c3 :c4 :c5]]
        (let [dep (p2/dependence-of network c)]
          (is (= #{:c0} (p2/edge-chain-origins dep))
              (str c " should inherit origin :c0"))
          (is (= #{c} (p2/edge-chain-tail dep))
              (str c " tail should be itself"))))
      ;; each of the five id props should have built topology once
      (let [prop-ids (into []
                           (keep (fn [[id entry]]
                                   (when (p2/propagator-f? entry) id)))
                           (p2/network-value network))]
        (is (= 5 (count prop-ids)))
        (doseq [id prop-ids]
          (is (p2/topology-built? (p2/lookup-node network id))
              (str "propagator " id " should be wired once"))
          (let [dep (p2/node-inputs (p2/lookup-node network id))]
            (is (= #{:c0} (p2/edge-chain-origins dep))
                (str id " should inherit origin :c0"))))
        ;; constructor names: output cell is the default dict name
        (doseq [c [:c1 :c2 :c3 :c4 :c5]]
          (let [named (p2/lookup-name network c)
                pf (p2/lookup-value network named)]
            (is (some? named) (str "dict should name prop by output " c))
            (is (p2/propagator-f? pf))
            (is (= c (:name pf) (p2/propagator-output pf)))))))))

(deftest id-chain-debug-runner-logs-and-settles
  (testing "debug runner prints steps and still carries the seed"
    (let [cells [:c0 :c1 :c2 :c3 :c4 :c5]
          seed 42
          network (p2/install-id-chain p2/empty-network cells)
          [tasks network] (p2/tell-debug network :c0 seed)
          network (p2/run-tasks-debug network tasks)]
      (is (= seed (p2/cell-value network :c5)))
      (is (p2/chain-complete? network :c5)))))

(deftest smart-skip-when-boundary-already-satisfied
  (testing "primitive id does not build if input and output are already concrete"
    (let [network (p2/install-id p2/empty-network :a :b)
          [_ network] (p2/tell network :b 7)
          [tasks network] (p2/tell network :a 7)
          prop-id (first tasks)
          network (p2/run-tasks network tasks)]
      (is (some? prop-id))
      (is (not (p2/topology-built? (p2/lookup-node network prop-id))))
      (is (= 7 (p2/cell-value network :a)))
      (is (= 7 (p2/cell-value network :b))))))

(deftest compound-expands-unless-boundary-satisfied
  (testing "compound id-bridge expands when out is nothing, skips when out satisfied"
    (let [network (p2/install-compound-id-bridge p2/empty-network :in :out 3)
          compound-id (first (keep (fn [[id v]]
                                     (when (p2/compound-f? v) id))
                                   (p2/network-value network)))
          ;; path A: only input told → expand
          [t1 n1] (p2/tell network :in 9)
          n1 (p2/run-tasks n1 t1)]
      (is (p2/topology-built? (p2/lookup-node n1 compound-id)))
      (is (= 9 (p2/cell-value n1 :out)))
      ;; path B: both ends told first → smart skip, never expand
      (let [network (p2/install-compound-id-bridge p2/empty-network :x :y 3)
            compound-id (first (keep (fn [[id v]]
                                       (when (p2/compound-f? v) id))
                                     (p2/network-value network)))
            [_ network] (p2/tell network :y 3)
            [tasks network] (p2/tell network :x 3)
            network (p2/run-tasks network tasks)
            inner-props (into []
                              (keep (fn [[id v]]
                                      (when (p2/propagator-f? v) id))
                                    (p2/network-value network)))]
        (is (not (p2/topology-built? (p2/lookup-node network compound-id))))
        (is (empty? inner-props))
        (is (= 3 (p2/cell-value network :x)))
        (is (= 3 (p2/cell-value network :y)))))))

(deftest bi-sync-chain-of-eight-debug
  (testing "8-cell bi-sync chain: forward builds, reverse smart-skips, logs to console"
    (let [cells [:c0 :c1 :c2 :c3 :c4 :c5 :c6 :c7]
          seed 88
          network (p2/install-bi-sync-chain p2/empty-network cells)
          [tasks network] (p2/tell-debug network :c0 seed)
          network (p2/run-tasks-debug network tasks)
          props (into []
                      (keep (fn [[id v]]
                              (when (p2/propagator-f? v) id))
                            (p2/network-value network)))
          built (filterv #(p2/topology-built? (p2/lookup-node network %)) props)
          pending (filterv #(not (p2/topology-built? (p2/lookup-node network %))) props)]
      (doseq [c cells]
        (is (= seed (p2/cell-value network c))))
      ;; 7 pairs × 2 directions = 14 pending ids installed
      (is (= 14 (count props)))
      ;; one-way wave builds forward edges; reverse edges stay pending (smart skip)
      (is (= 7 (count built)))
      (is (= 7 (count pending)))
      (is (p2/chain-complete? network :c7)))))

(deftest church-cons-visitor-car-cdr
  (testing "cons waits for visitor-network; car/cdr bi-sync a and b"
    (let [{:keys [network visitor a b pair]} (p2/install-cons p2/empty-network :a :b :pair)
          [t1 network] (p2/tell network a "L")
          [t2 network] (p2/tell network b "R")
          network (p2/run-tasks network (into t1 t2))
          ;; first apply via visitor cell → compound expands
          [t-car network] (p2/install-car network visitor :car-out)
          network (p2/run-tasks network t-car)
          ;; second apply via apply-visitor (compound already built)
          [t-cdr network] (p2/install-cdr network a b :cdr-out)
          network (p2/run-tasks network t-cdr)]
      (is (some? (p2/lookup-name network pair)))
      (is (p2/visitor? (p2/cell-value network visitor)))
      (is (= "L" (p2/cell-value network :car-out)))
      (is (= "R" (p2/cell-value network :cdr-out)))
      (is (= "L" (p2/cell-value network a)))
      (is (= "R" (p2/cell-value network b))))))

(deftest church-cons-non-visitor-logs-and-skips
  (testing "non-Visitor in visitor cell: log, no expand, compound stays unbuilt"
    (let [{:keys [network visitor a b]} (p2/install-cons p2/empty-network :a :b :pair)
          compound-id (p2/lookup-name network :pair)
          [t1 network] (p2/tell network a 1)
          [t2 network] (p2/tell network b 2)
          network (p2/run-tasks network (into t1 t2))
          [tasks network] (p2/tell network visitor :not-a-visitor)
          network (p2/run-tasks network tasks)
          props-before (count (filter p2/propagator-f? (vals (p2/network-value network))))]
      (is (some? compound-id))
      (is (not (p2/topology-built? (p2/lookup-node network compound-id))))
      (is (zero? props-before))
      ;; real visitor later still expands
      (let [[t-car network] (p2/install-car network visitor :car-out)
            network (p2/run-tasks network t-car)]
        (is (p2/topology-built? (p2/lookup-node network compound-id)))
        (is (= 1 (p2/cell-value network :car-out)))))))

(deftest p-list-empty-complete-and-partial
  (testing " '() terminates; nothing on cdr spine means partial"
    (is (p2/empty-list? p2/empty-list))
    (is (not (p2/nothing? p2/empty-list)))
    ;; empty cell is a completed list
    (let [[_ network] (p2/tell p2/empty-network :xs p2/empty-list)]
      (is (p2/p-list? network :xs))
      (is (p2/p-list-completed? network :xs))
      (is (not (p2/p-list-partial? network :xs))))
    ;; single cons … → '() is complete; open cdr is partial
    (let [{:keys [network a b list]} (p2/install-cons p2/empty-network :a1 :b1)
          [t1 network] (p2/tell network a "x")
          network (p2/run-tasks network t1)]
      (is (p2/p-list? network list))
      (is (p2/p-list-partial? network list))
      (is (not (p2/p-list-completed? network list)))
      (let [[t2 network] (p2/tell network b p2/empty-list)
            network (p2/run-tasks network t2)]
        (is (p2/p-list-completed? network list))
        (is (not (p2/p-list-partial? network list)))))
    ;; two-cons chain: outer cdr holds inner Church cons (lazy list node)
    (let [inner (p2/install-cons p2/empty-network :a2 :b2)
          network (:network inner)
          [t network] (p2/tell network (:a inner) 2)
          [t network] (let [[t' n] (p2/tell network (:b inner) p2/empty-list)]
                        [(into t t') n])
          network (p2/run-tasks network t)
          outer (p2/install-cons network :a1 :b1)
          network (:network outer)
          [t network] (p2/tell network (:a outer) 1)
          [t network] (let [[t' n] (p2/tell network (:b outer)
                                            (p2/cell-value network (:list inner)))]
                        [(into t t') n])
          network (p2/run-tasks network t)]
      (is (p2/p-list-completed? network (:list outer)))
      (is (p2/church-cons? (p2/cell-value network (:list outer))))
      (is (= 1 (p2/cell-value network (:a outer))))
      (is (= 2 (p2/cell-value network (:a inner)))))))
