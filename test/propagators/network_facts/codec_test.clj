(ns propagators.network-facts.codec-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.graph :as graph]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.network-facts.export :as e]
            [propagators.network-facts.import :as i]
            [propagators.network-facts.support :as h]
            [propagators.propagator :as prop]
            [propagators.relationship :as relationship]))

(deftest empty-and-isolated-round-trip
  (doseq [network [net/empty-net
                   (nb/install-cell net/empty-net (ids/new-node-id))
                   (assoc net/empty-net :graph {(ids/new-node-id) (graph/blank-node)})]]
    (let [facts (e/net->facts network)]
      (is (every? #(= 3 (count %)) facts))
      (is (:valid? (i/validate-facts facts)))
      (is (= network (i/facts->net facts)))
      (is (not (identical? network (i/facts->net facts)))))))

(deftest all-fields-metadata-extra-fields-and-qualified-cycles
  (let [a (ids/new-node-id) b (ids/new-node-id)
        [p installed] (h/copy-prop (nb/install-cells [a b]) a b)
        parent (relationship/node-key [:outer] p)
        child (relationship/node-key [:cell a] b)
        network (-> installed
                    (assoc :relationship (-> {} (relationship/relate parent child)
                                             (relationship/relate child parent)))
                    (assoc :dict {'answer b :opaque {:nested net/empty-net}} :extra [:user 1])
                    (update :graph with-meta {:graph true})
                    (update-in [:graph a :outputs] with-meta {:adjacency true})
                    (update :env with-meta {:env true})
                    (update :dict with-meta {:dict true})
                    (update :relationship with-meta {:relationship true})
                    (update-in [:graph a] #(with-meta (assoc % :color :blue) {:node true}))
                    (update-in [:env a] #(with-meta (assoc % :receipt :input) {:cell true}))
                    (update-in [:env p] #(with-meta (assoc % :purpose :copy) {:prop true}))
                    (with-meta {:net true}))
        rebuilt (i/facts->net (e/net->facts network))]
    (is (= network rebuilt))
    (is (= (type network) (type rebuilt)))
    (is (= {:adjacency true} (meta (get-in rebuilt [:graph a :outputs]))))
    (doseq [path [[] [:graph] [:env] [:dict] [:relationship] [:graph a] [:env a] [:env p]]]
      (is (= (meta (get-in network path network)) (meta (get-in rebuilt path rebuilt)))))
    (is (identical? (get-in network [:env p :activate]) (get-in rebuilt [:env p :activate])))))

(deftest opaque-values-and-no-execution
  (let [a (ids/new-node-id) b (ids/new-node-id) calls (atom 0)
        activation (fn [& _] (swap! calls inc) [])
        nested (nb/install-cell net/empty-net (ids/new-node-id))
        payload {:function activation :nested nested :support {:premises #{:one}}}
        [p network] ((prop/construct-propagator :opaque activation [a] [b])
                     (nb/seed-cell (nb/install-cells [a b]) a payload))
        rebuilt (i/facts->net (e/net->facts network))]
    (is (identical? payload (get-in rebuilt [:env a :content])))
    (is (identical? nested (get-in rebuilt [:env a :content :nested])))
    (is (identical? (get-in network [:env p :activate]) (get-in rebuilt [:env p :activate])))
    (is (= 0 @calls))))

(deftest plain-record-maps-and-opaque-entries
  (let [id (ids/new-node-id)
        network {:graph {id {:inputs #{} :outputs #{} :extra 1}}
                 :env {id {:content value/nothing :strongest value/nothing}}
                 :dict {:nested []} :relationship {} :extra :map}
        opaque (assoc-in network [:env id] [:not-a-cell net/empty-net])]
    (is (= network (i/facts->net (e/net->facts network))))
    (is (= opaque (i/facts->net (e/net->facts opaque))))
    (is (not (record? (i/facts->net (e/net->facts network)))))))

(deftest malformed-triples-cardinality-and-adjacency
  (let [a (ids/new-node-id) b (ids/new-node-id)
        network (nb/install-cells [a b])
        facts (e/net->facts network)]
    (is (= :invalid-triples (h/reason #(i/validate-facts #{[:broken :triple]}))))
    (is (= :invalid-attribute
           (h/reason #(i/validate-facts (conj facts [[:env a] :cell/content 42])))))
    (is (= :malformed-adjacency
           (h/reason #(i/validate-facts (conj facts [[:graph a] :graph/output [:graph b]])))))
    (is (= :unknown-entity
           (h/reason #(i/validate-facts (conj facts [[:unknown] :unknown/key 1])))))))

(deftest recipe-ordered-ports-and-factory-not-run-on-round-trip
  (let [a (ids/new-node-id) b (ids/new-node-id) c (ids/new-node-id)
        [p network] (h/copy-prop (nb/install-cells [a b c]) a b)
        calls (atom 0)
        factory (fn [params inputs outputs]
                  (swap! calls inc) (h/copy-factory params inputs outputs))
        facts (into (e/net->facts network) (h/recipe-facts p :identity factory [] [a] [b]))]
    (is (= network (i/facts->net facts)))
    (is (= 0 @calls))
    (is (= :recipe-boundary-mismatch
           (h/reason #(i/validate-facts
                       (h/replace-value facts [:port p :input 0] :port/cell c)))))
    (is (= :invalid-port-order
           (h/reason #(i/validate-facts
                       (h/replace-value facts [:port p :input 0] :port/position 2)))))))

(deftest arbitrary-dictionary-payloads-remain-opaque-on-round-trip
  (doseq [payload [nil 42 (fn [& _] :opaque) {:arbitrary [:payload]}]]
    (let [network (assoc net/empty-net :dict {:slot-declarations payload})
          rebuilt (i/facts->net (e/net->facts network))]
      (is (= network rebuilt))
      (is (identical? payload (get-in rebuilt [:dict :slot-declarations]))))))
