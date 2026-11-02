(ns propagators.relationship-snapshot-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler-2.model.env.index :as lexical-index]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.relationship :as relationship]
            [propagators.relationship-observer :as observer]))

(deftest snapshot-shares-label-indexes-and-refreshes-them-for-the-next-source
  (let [environment-id (ids/new-node-id)
        a (ids/new-node-id)
        b (ids/new-node-id)
        a-key (relationship/node-key [:outer] a)
        b-key (relationship/node-key [:outer] b)
        topology (fn [left-name]
                   {:frames {environment-id
                             {:bindings {left-name #{a} 'right #{b}}
                              :current-bindings {left-name a 'right b}}}})
        source (-> net/empty-net
                   (nb/install-cell a 1 1)
                   (nb/install-cell b 2 2)
                   (net/assoc-net-dict-entry
                    :propagators.compiler-2.model.env.index/lexical-topology
                    (topology 'left)))
        updated (-> source
                    (nb/install-cell a 3 3)
                    (net/assoc-net-dict-entry
                     :propagators.compiler-2.model.env.index/lexical-topology
                     (topology 'renamed-left)))
        calls (atom 0)
        runtime-topology lexical-index/runtime-topology]
    (with-redefs [lexical-index/runtime-topology
                  (fn [owner]
                    (swap! calls inc)
                    (runtime-topology owner))]
      (let [before (observer/snapshot source #{a-key b-key})
            after (observer/snapshot updated #{a-key b-key})]
        (is (= 2 @calls) "One metadata acquisition per owner in each sample")
        (is (= 'left (get (:nodes before) a-key)))
        (is (= 'renamed-left (get (:nodes after) a-key)))
        (is (= 'right (get (:nodes before) b-key) (get (:nodes after) b-key)))
        (is (= {a-key 1 b-key 2} (:values before)))
        (is (= {a-key 3 b-key 2} (:values after)))
        (is (= (:edges before) (:edges after)))))))
