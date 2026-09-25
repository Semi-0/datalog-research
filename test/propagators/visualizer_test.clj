(ns propagators.visualizer-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.datastructures.event :as event]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]
            [propagators.runner :as runner]
            [propagators.visualizer :as visualizer]))

(defn- node-id [] (ids/new-node-id))

(defn- run
  [network tasks]
  (runner/completed-network (runner/run-network tasks network)))

(deftest cell-window-resolves-current-content-and-strongest
  (let [source (node-id)
        port (node-id)
        network0 (nb/install-cell net/empty-net source {:claim 1} 1)
        [prop-id installed] ((visualizer/p:cell-window source port) network0)
        settled (run installed [prop-id])
        declaration (net/network-cell-strongest settled port)
        resolved (visualizer/resolve-view settled declaration)]
    (is (= :cell-window (:view/type resolved)))
    (is (= {:claim 1} (:view/content resolved)))
    (is (= 1 (:view/strongest resolved)))))

(deftest cell-history-retains-content-and-strongest-by-runtime-time
  (let [source (node-id)
        port (node-id)
        [prop-id installed]
        ((visualizer/p:cell-history source port)
         (-> net/empty-net
             (nb/install-cell source)
             (net/assoc-net-dict-entry :program/epoch 3)
             (net/assoc-net-dict-entry :runtime/commit-tick 10)))
        [first-net first-tasks]
        (nb/seed-cell! installed tq/empty-queue source
                       (event/active-event source :test 10 :first))
        first-settled (run first-net first-tasks)
        advanced (net/assoc-net-dict-entry first-settled
                                            :runtime/commit-tick 11)
        [second-net second-tasks]
        (nb/seed-cell! advanced tq/empty-queue source
                       (event/active-event source :test 11 :second))
        settled (run second-net second-tasks)
        declaration (net/network-cell-strongest settled port)
        samples (:view/samples (visualizer/resolve-view settled declaration))]
    (is (= prop-id (visualizer/stable-id
                    :visualizer :cell-history source port)))
    (is (= [10 11] (mapv :sample/tick samples)))
    (is (= 2 (count samples)))
    (is (every? #(contains? % :sample/content) samples))
    (is (every? #(contains? % :sample/strongest) samples))))

(deftest structural-references-drive-a-native-relationship-hierarchy
  (let [source (node-id)
        output (node-id)
        parent-id (node-id)
        child-id (node-id)
        refs (node-id)
        hierarchy (node-id)
        network0 (-> net/empty-net
                     (nb/install-cell source 1 1)
                     (nb/install-cell output))
        [_ network1]
        (nb/install-propagator
         network0
         (prop/construct-propagator parent-id :test/parent
                                    (fn [_ _ _] []) [source] [output]))
        [_ network2]
        (nb/install-propagator
         network1
         (prop/construct-propagator child-id :test/child
                                    (fn [_ _ _] []) [] []))
        parent-key (relationship/node-key [:outer] parent-id)
        child-key (relationship/node-key [:outer] child-id)
        network3 (net/update-net-relationship network2 relationship/relate
                                               parent-key child-key)
        [refs-id network4]
        ((visualizer/p:propagator-references output :inputs refs) network3)
        [hierarchy-id network5]
        ((visualizer/p:hierarchy refs hierarchy) network4)
        settled (run network5 [refs-id hierarchy-id])
        resolved (visualizer/resolve-view
                  settled
                  (net/network-cell-strongest settled hierarchy))]
    (is (= [parent-key] (:view/roots resolved)))
    (is (contains? (set (get-in resolved [:view/graph :edges]))
                   [parent-key child-key]))
    (is (= #{parent-key child-key}
           (set (keys (get-in resolved [:view/graph :nodes])))))))

(deftest juxtapose-preserves-order-and-rejects-cycles
  (let [source (node-id)
        left (node-id)
        right (node-id)
        combined (node-id)
        network (-> net/empty-net
                    (nb/install-cell source 1 1)
                    (nb/install-cell left
                                     (visualizer/cell-window-declaration :left source)
                                     (visualizer/cell-window-declaration :left source))
                    (nb/install-cell right
                                     (visualizer/cell-window-declaration :right source)
                                     (visualizer/cell-window-declaration :right source))
                    (nb/install-cell combined
                                     (visualizer/juxtapose-declaration
                                      :combined [left right])
                                     (visualizer/juxtapose-declaration
                                      :combined [left right])))
        resolved (visualizer/resolve-view
                  network (net/network-cell-strongest network combined))]
    (is (= [:left :right]
           (mapv :view/id (:view/resolved-children resolved))))
    (testing "a view-port cycle is rejected"
      (let [cyclic (nb/install-cell
                    network left
                    (visualizer/juxtapose-declaration :cycle [combined right])
                    (visualizer/juxtapose-declaration :cycle [combined right]))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"cyclic declarative view"
                              (visualizer/resolve-view
                               cyclic
                               (net/network-cell-strongest cyclic combined))))))))
