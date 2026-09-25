(ns propagators.relationship-observer-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.graph :as graph]
            [propagators.experimental.runner.compound-chain :as chain]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-patch :as patch]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]
            [propagators.relationship-observer :as observer]
            [propagators.runner :as runner]))

(defn- cell-id [] (ids/new-node-id))

(defn- propagator-id-by-name
  [network expected-name]
  (some (fn [[id entry]]
          (when (and (prop/prop? entry)
                     (= expected-name (prop/prop-name entry)))
            id))
        (net/net-env network)))

(deftest selectors-read-relationships-from-current-net
  (let [parent-id (cell-id)
        child-id (cell-id)
        other-id (cell-id)
        parent (relationship/node-key [:outer] parent-id)
        child (relationship/node-key [:outer] child-id)
        network (-> net/empty-net
                    (nb/install-cell parent-id 1 1)
                    (nb/install-cell child-id 2 2)
                    (nb/install-cell other-id 3 3)
                    (net/update-net-relationship relationship/relate parent child))]
    (is (= #{parent child}
           ((observer/child-node-keys parent) network)))
    (is (= #{parent (relationship/node-key [:outer] other-id)}
           (observer/root-node-keys network)))))

(deftest snapshot-includes-network-and-structural-edges
  (let [a (cell-id)
        b (cell-id)
        p (cell-id)
        parent (relationship/node-key [:outer] p)
        child (relationship/node-key [:outer] b)
        network (-> net/empty-net
                    (nb/install-cell a 1 1)
                    (nb/install-cell b 2 2)
                    (nb/install-cell p)
                    (net/net-with-graph
                     {a (graph/node #{} #{b})
                      b (graph/node #{a} #{})
                      p (graph/node #{} #{})})
                    (net/update-net-relationship relationship/relate parent child))
        traced (observer/snapshot network
                                  #{(relationship/node-key [:outer] a)
                                    child
                                    parent})]
    (is (= 3 (count (:nodes traced))))
    (is (contains? (set (:edges traced))
                   [(relationship/node-key [:outer] a) child]))
    (is (contains? (set (:edges traced)) [parent child]))
    (is (= 2 (get (:values traced) child)))))

(deftest observer-reacts-to-its-input-and-reads-current-net
  (let [source (cell-id)
        output (cell-id)
        observer-id (observer/stable-node-id :test source output)
        network0 (-> net/empty-net
                     (nb/install-cell source 1 1)
                     (nb/install-cell output))
        [_ network1]
        (nb/install-propagator
         network0
         (observer/p:observe-network
          observer-id source output
          (observer/snapshot-of
           #(observer/root-node-keys % #{output}))))
        initial (runner/completed-network
                 (runner/run-network [observer-id] network1))
        [updated tasks] (nb/seed-cell! initial tq/empty-queue source 2)
        settled (runner/completed-network (runner/run-network tasks updated))
        graph-value (net/network-cell-value settled output)
        source-key (relationship/node-key [:outer] source)]
    (is (= 2 (get (:values graph-value) source-key)))
    (is (= observer/observer-name
           (prop/prop-name
           (net/network-lookup-propagator settled observer-id))))))

(deftest root-observer-refreshes-from-every-cell-in-the-selected-component
  (let [source (cell-id)
        derived (cell-id)
        output (cell-id)
        compute-id (cell-id)
        network0 (-> net/empty-net
                     (nb/install-cell source)
                     (nb/install-cell derived)
                     (nb/install-cell output))
        [_ network1]
        (nb/install-propagator
         network0
         (prop/construct-propagator
          compute-id :test/increment
          (fn [_inputs _outputs network]
            [(message derived
                      (inc (net/network-cell-strongest network source)))])
          [source]
          [derived]))
        [initial-id observed]
        ((observer/p:observe-roots [source] output) network1)
        initialized (runner/completed-network
                     (runner/run-network [initial-id] observed))
        [updated tasks] (nb/seed-cell! initialized tq/empty-queue source 2)
        settled (runner/completed-network (runner/run-network tasks updated))
        traced (net/network-cell-value settled output)]
    (is (= 3 (get (:values traced)
                  (relationship/node-key [:outer] derived))))))

(deftest child-observer-decorates-only-the-selected-parent
  (let [parent-id (cell-id)
        untouched-id (cell-id)
        output (cell-id)
        child (cell-id)
        activation (fn [_inputs _outputs _network]
                     [(patch/declare-cell child)])
        network0 (-> net/empty-net
                     (nb/install-cell output))
        [_ network1] (nb/install-propagator
                      network0
                      (prop/construct-propagator
                       parent-id :test/parent activation [] []))
        [_ network2] (nb/install-propagator
                      network1
                      (prop/construct-propagator
                       untouched-id :test/untouched activation [] []))
        original-untouched (prop/prop-f
                            (net/network-lookup-propagator network2 untouched-id))
        [_ observed] ((observer/p:observe-children
                       (relationship/node-key [:outer] parent-id)
                       output)
                      network2)
        execution (runner/run-network [parent-id] observed)
        settled (runner/completed-network execution)
        child-observer-id (observer/observer-id output :child child)]
    (is (contains? (net/net-env settled) child))
    (is (contains? (net/net-env settled) child-observer-id))
    (is (identical? original-untouched
                    (prop/prop-f
                     (net/network-lookup-propagator settled untouched-id))))))

(deftest repeated-child-declaration-keeps-one-stable-observer
  (let [parent-id (cell-id)
        output (cell-id)
        child (cell-id)
        activation (fn [_inputs _outputs _network]
                     [(patch/declare-cell child)])
        network0 (nb/install-cell net/empty-net output)
        [_ network1] (nb/install-propagator
                      network0
                      (prop/construct-propagator
                       parent-id :test/parent activation [] []))
        [_ observed] ((observer/p:observe-children
                       (relationship/node-key [:outer] parent-id)
                       output)
                      network1)
        once (runner/completed-network
              (runner/run-network [parent-id] observed))
        twice (runner/completed-network
               (runner/run-network [parent-id] once))
        child-observer-id (observer/observer-id output :child child)]
    (is (= child-observer-id
           (->> (net/net-env twice)
                (keep (fn [[id entry]]
                        (when (= observer/observer-name
                                 (and (prop/prop? entry)
                                      (prop/prop-name entry)))
                          id)))
                (filter #{child-observer-id})
                first)))
    (is (= (net/net-relationship once)
           (net/net-relationship twice)))))

(deftest compound-slot-children-are-visible-through-the-composed-observer
  (let [built (chain/build-vanilla-chain 1)
        parent-id (propagator-id-by-name
                   (:network built)
                   [:compound-object/network-slot :car])
        parent-key (relationship/node-key [:outer] parent-id)
        trace-output (cell-id)
        [initial-id observed]
        ((observer/p:observe-children parent-key trace-output)
         (:network built))
        settled (runner/completed-network
                 (runner/run-network
                  (tq/enqueue (:tasks built) initial-id)
                  observed))
        traced (net/network-cell-value settled trace-output)
        labels (set (vals (:nodes traced)))
        parent-target-labels
        (into #{}
              (keep (fn [[from to]]
                      (when (= parent-key from)
                        (get (:nodes traced) to))))
              (:edges traced))]
    (is (contains? labels
                   [:compound-object/slot-sync :car :to-canonical]))
    (is (contains? labels
                   [:compound-object/slot-sync :car :from-canonical]))
    (is (contains? parent-target-labels
                   [:compound-object/slot-sync :car :to-canonical]))
    (is (contains? parent-target-labels
                   [:compound-object/slot-sync :car :from-canonical]))))
