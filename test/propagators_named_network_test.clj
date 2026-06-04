(ns propagators-named-network-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.bool4 :as bool4]
            [propagators.cells.cell :as cell]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.datastructures.evidence-set :as evidence]
            [propagators.datastructures.named-network :as named]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.boundary :as boundary]
            [propagators.stdlib.prop :as stdlib-prop]))

(defn- named-cell-net
  [named-values]
  (reduce
   (fn [n [k v]]
     (let [id (new-node-id)]
       (-> n
           (net/net-with-dict (assoc (net/net-dict-or-empty n) k id))
           (net/assoc-net-cell id (cell/cell v v)))))
   net/empty-net
   named-values))

(defn- named-prop-net
  [k id]
  (-> net/empty-net
      (net/net-with-dict {k id})
      (net/assoc-net-prop id (prop/prop (fn [_inputs _outputs _network] [])))))

(defn- prop-ids
  [n]
  (->> (net/net-env n)
       (keep (fn [[id entry]]
               (when (prop/prop? entry)
                 id)))
       vec))

(defn- install-cells
  [n ids]
  (reduce
   (fn [n id]
     (second ((cell/construct-cell id) n)))
   n
   ids))

(defn- p-id-value-sync-net
  []
  (let [from (new-node-id)
        to (new-node-id)
        n (install-cells net/empty-net [from to])
        [sync-id n] ((stdlib-prop/id from to) n)]
    {:net n
     :from from
     :to to
     :sync-id sync-id}))

(defn- bi-sync-value-sync-net
  []
  (let [left (new-node-id)
        right (new-node-id)
        out-left (new-node-id)
        out-right (new-node-id)
        n (install-cells net/empty-net [left right out-left out-right])
        n (boundary/bi-sync nil [left right] [out-left out-right] n)
        [left-sync right-sync] (prop-ids n)]
    {:net n
     :left left
     :right right
     :out-left out-left
     :out-right out-right
     :sync-ids [left-sync right-sync]}))

(defn- add-named-cell
  [n k v]
  (let [id (new-node-id)]
    (-> (second ((cell/construct-cell id v v) n))
        (net/net-with-dict (assoc (net/net-dict-or-empty n) k id)))))

(defn- seed-cell
  [n cell-id v]
  (net/assoc-net-cell n cell-id (cell/cell v v)))

(defn- run-props
  [n prop-ids]
  (run-tasks (tq/enqueue-all tq/empty-queue prop-ids) n))

(defn- strongest
  [n cell-id]
  (cell/cell-strongest (net/env-get (net/net-env n) cell-id)))

(defn- content
  [n cell-id]
  (cell/cell-content (net/env-get (net/net-env n) cell-id)))

(defn- propagate-value
  [n prop-ids from v]
  (run-props (seed-cell n from v) prop-ids))

(deftest named-network-interface-subsumption
  (testing "a subsumes b when a has every named key in b with stronger values"
    (let [a (named-cell-net [[:x bool4/contradiction] [:y true]])
          b (named-cell-net [[:x true]])]
      (is (= true (named/named-network->= a b)))
      (is (= false (named/named-network->= b a))))))

(deftest named-network-propagators-compare-by-id
  (testing "same named propagator must resolve to the same id"
    (let [id (new-node-id)
          a (named-prop-net :p id)
          b (named-prop-net :p id)
          c (named-prop-net :p (new-node-id))]
      (is (= true (named/named-network->= a b)))
      (is (= bool4/contradiction (named/named-network->= a c))))))

(deftest named-network-cell-merge-keeps-stronger-side
  (testing "incoming update replaces content when it subsumes the current content"
    (let [content (named-cell-net [[:x true]])
          update (named-cell-net [[:x bool4/contradiction]])
          merged (merge/cell-merge content update net/empty-net)]
      (is (= #{update} merged))
      (is (= update (merge/strongest-value merged net/empty-net)))))

  (testing "current content stays when it subsumes the incoming update"
    (let [content (named-cell-net [[:x bool4/contradiction]])
          update (named-cell-net [[:x true]])
          merged (merge/cell-merge content update net/empty-net)]
      (is (= #{content} merged))
      (is (= content (merge/strongest-value merged net/empty-net))))))

(deftest named-network-cell-merge-normalizes-into-evidence-set
  (testing "nothing plus one named network becomes a singleton evidence set"
    (let [update (named-cell-net [[:x true]])
          merged (merge/cell-merge value/nothing update net/empty-net)]
      (is (= #{update} merged))
      (is (= update (merge/strongest-value merged net/empty-net))))))

(deftest named-network-value-syncs-through-p-id
  (testing "p:id propagates a named-network value and later replaces it with a subsuming update"
    (let [{:keys [net from to sync-id]} (p-id-value-sync-net)
          weak (named-cell-net [[:x true]])
          strong (add-named-cell weak :y false)
          after-weak (propagate-value net [sync-id] from weak)
          after-strong (propagate-value after-weak [sync-id] from strong)]
      (is (= true (named/named-network->= strong weak)))
      (is (= false (named/named-network->= weak strong)))
      (is (= #{weak} (content after-weak to)))
      (is (= weak (strongest after-weak to)))
      (is (= #{strong} (content after-strong to)))
      (is (= strong (strongest after-strong to))))))

(deftest named-network-value-syncs-through-bi-sync
  (testing "bi-sync propagates named-network values and accepts subsuming updates"
    (let [{:keys [net left right out-left out-right sync-ids]} (bi-sync-value-sync-net)
          weak (named-cell-net [[:x true]])
          strong (add-named-cell weak :y false)
          left-weak (propagate-value net sync-ids left weak)
          left-strong (propagate-value left-weak sync-ids left strong)
          right-weak (propagate-value left-strong sync-ids right weak)
          right-strong (propagate-value right-weak sync-ids right strong)]
      (is (= true (named/named-network->= strong weak)))
      (is (= false (named/named-network->= weak strong)))
      (is (= #{weak} (content left-weak out-right)))
      (is (= weak (strongest left-weak out-right)))
      (is (= #{strong} (content left-strong out-right)))
      (is (= strong (strongest left-strong out-right)))
      (is (= #{weak} (content right-weak out-left)))
      (is (= weak (strongest right-weak out-left)))
      (is (= #{strong} (content right-strong out-left)))
      (is (= strong (strongest right-strong out-left))))))

(deftest named-network-evidence-set-merge-maintains-antichain
  (testing "stronger update replaces weaker evidence"
    (let [weak (named-cell-net [[:x true]])
          strong (named-cell-net [[:x bool4/contradiction]])
          merged (merge/cell-merge #{weak} strong net/empty-net)]
      (is (= #{strong} merged))))

  (testing "weaker update is rejected by stronger evidence"
    (let [strong (named-cell-net [[:x bool4/contradiction]])
          weak (named-cell-net [[:x true]])
          merged (merge/cell-merge #{strong} weak net/empty-net)]
      (is (= #{strong} merged))))

  (testing "incomparable update is added to the evidence set"
    (let [a (named-cell-net [[:x true]])
          b (named-cell-net [[:y false]])
          merged (merge/cell-merge #{a} b net/empty-net)]
      (is (= #{a b} merged)))))

(deftest named-network-cell-merge-joins-incomparable-interfaces
  (testing "disjoint named commitments are kept by joining both networks"
    (let [content (named-cell-net [[:x true]])
          update (named-cell-net [[:y false]])
          merged (merge/cell-merge content update net/empty-net)
          strongest (merge/strongest-value merged net/empty-net)]
      (is (evidence/evidence-set? merged))
      (is (= #{content update} merged))
      (is (named/named-network? strongest))
      (is (= #{:x :y} (set (keys (net/net-dict-or-empty strongest))))))))

(deftest named-network-cell-merge-joins-incomparable-cell-values
  (testing "same named cell with incomparable Bool4 values keeps evidence and joins strongest"
    (let [content (named-cell-net [[:x true]])
          update (named-cell-net [[:x false]])
          merged (merge/cell-merge content update net/empty-net)
          strongest (merge/strongest-value merged net/empty-net)
          x-id (get (net/net-dict-or-empty strongest) :x)
          x-cell (get (net/net-env strongest) x-id)]
      (is (evidence/evidence-set? merged))
      (is (= #{content update} merged))
      (is (= #{:x} (set (keys (net/net-dict-or-empty strongest)))))
      (is (value/contradiction? (cell/cell-strongest x-cell))))))

(deftest named-network-cell-merge-contradicts-on-same-name-different-prop-id
  (testing "same named propagator with different ids contradicts in strongest, not content"
    (let [content (named-prop-net :p (new-node-id))
          update (named-prop-net :p (new-node-id))
          merged (merge/cell-merge content update net/empty-net)]
      (is (evidence/evidence-set? merged))
      (is (= #{content update} merged))
      (is (value/contradiction?
           (merge/strongest-value merged net/empty-net))))))
