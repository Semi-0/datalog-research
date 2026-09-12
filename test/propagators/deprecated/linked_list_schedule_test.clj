(ns propagators.deprecated.linked-list-schedule-test
  "Deprecated task-queue experiments for the deprecated p:cons-scheduled path.

  This opt-in namespace is executable historical evidence. It is excluded from
  regular suites, and its known install-time scheduling failure is not a
  compatibility or release gate.

  Run: clj -M:test propagators.deprecated.linked-list-schedule-test"
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell :as cell :refer [construct-cell]]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.cells.value :as value]
            [propagators.core :refer [run-tasks]]
            [propagators.deprecated.compound-data :as cd]
            [propagators.graph :as graph]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :refer [new-node-id]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- neighbor-prop-ids [n cell-id]
  (let [g (net/net-graph n)]
    (when (contains? g cell-id)
      (let [node (graph/get-node g cell-id)
            adj (set/union (graph/node-input-ids node) (graph/node-output-ids node))
            env (net/net-env n)]
        (vec (filter #(prop/prop? (get env %)) adj))))))

(defn- enqueue-prop-ids [tasks prop-ids]
  (reduce tq/enqueue tasks prop-ids))

(defn- install-cell [n id content strongest]
  (second ((construct-cell id content strongest) n)))

(defn- install-cell!
  [n tasks id content strongest]
  (let [[cell-id n'] ((construct-cell id content strongest) n)
        tasks' (enqueue-prop-ids tasks (neighbor-prop-ids n' cell-id))]
    [n' tasks']))

(defn- install-prop!
  [n tasks installer]
  (let [result (installer n)
        [prop-id n'] (if (vector? result) result [nil result])
        tasks' (if prop-id (tq/enqueue tasks prop-id) tasks)]
    [n' tasks']))

(defn- seed-cells!
  [n tasks cell-value-pairs]
  (reduce (fn [[n' t] [id v]]
            (let [n'' (net/assoc-net-cell n' id (cell/cell v v))
                  t' (enqueue-prop-ids t (neighbor-prop-ids n'' id))]
              [n'' t']))
          [n tasks]
          cell-value-pairs))

(defn- run-from [n pending seed-ids]
  (let [g (net/net-graph n)
        tasks (tq/merge-queues pending (tq/into-queue (pop-inputs seed-ids g)))]
    (run-tasks tasks n)))

(defn- install-p:cons!
  [n tasks head-id tail-id collection-id]
  (let [[[car-id cdr-id linked-list-id] n']
         ((cd/p:cons-scheduled head-id tail-id collection-id) n)
         tasks' (enqueue-prop-ids tasks [car-id cdr-id linked-list-id])]
    [n' tasks']))

(defn- install-p:cons-deferred!
  [n head-id tail-id collection-id]
  (let [[[car-id cdr-id linked-list-id] n']
         ((cd/p:cons-scheduled head-id tail-id collection-id) n)]
    [n' [car-id cdr-id linked-list-id]]))

(defn- build-nested-with-cons-scheduled
  "`:schedule-cons? true` — enqueue car/cdr/linked-list at install.
  `:schedule-cons? :after-seed` — collect prop ids; enqueue in test after seed."
  ([layers] (build-nested-with-cons-scheduled layers {}))
  ([layers {:keys [schedule-cons?] :or {schedule-cons? true}}]
   (let [sentinel (new-node-id)
         ids (vec (repeatedly (+ (* 2 layers) 1) new-node-id))
         coll-ids (mapv #(nth ids (+ (* 2 %) 1)) (range layers))
         head-ids (mapv #(nth ids (* 2 %)) (range layers))
         n (-> net/empty-net
               (install-cell sentinel value/nothing value/nothing)
               (as-> n' (reduce (fn [n id] (install-cell n id value/nothing value/nothing))
                                n'
                                ids)))]
     (cond
       (= schedule-cons? :after-seed)
       (let [[net cons-prop-ids]
             (reduce
              (fn [[n ids] i]
                (let [h (head-ids i)
                      c (coll-ids i)
                      tail (if (< i (dec layers)) (coll-ids (inc i)) sentinel)
                      [n' layer-ids] (install-p:cons-deferred! n h tail c)]
                  [n' (into ids layer-ids)]))
              [n []]
              (range layers))]
         {:net net :cons-prop-ids cons-prop-ids :head-ids head-ids :coll-ids coll-ids})

       :else
       (let [[net tasks]
             (reduce
              (fn [[n t] i]
                (let [h (head-ids i)
                      c (coll-ids i)
                      tail (if (< i (dec layers)) (coll-ids (inc i)) sentinel)]
                  (install-p:cons! n t h tail c)))
              [n tq/empty-queue]
              (range layers))]
         {:net net :tasks tasks :head-ids head-ids :coll-ids coll-ids})))))

(defn- build-three-layer-accessor-on-scheduled-net
  [{:keys [net tasks head-ids coll-ids]}]
  (let [[coll0 coll1 coll2] coll-ids
        [head0 head1 head2] head-ids
        out (new-node-id)
        [n0 t0] (install-cell! net (or tasks tq/empty-queue) out value/nothing value/nothing)
        [n1 t1] (install-prop! n0 t0 (cd/p:cdr coll1 coll0))
        [n2 t2] (install-prop! n1 t1 (cd/p:cdr coll2 coll1))
        [n3 t3] (install-prop! n2 t2 (cd/p:car out coll2))]
    {:net n3 :tasks t3 :coll0 coll0 :head0 head0 :head1 head1 :head2 head2 :out out}))

(deftest p-cons-scheduled-enqueues-car-and-cdr-at-install
  (testing "p:cons-scheduled returns three ids; install-p:cons! enqueues all three"
    (let [head (new-node-id) tail (new-node-id) coll (new-node-id)
          n (-> net/empty-net
                (install-cell head value/nothing value/nothing)
                (install-cell tail value/nothing value/nothing)
                (install-cell coll value/nothing value/nothing))
          [_ tasks] (install-p:cons! n tq/empty-queue head tail coll)]
      (is (= 3 (count (:task-queue/q tasks)))))))

(defn ^:deprecated install-time-enqueue-accessor-result
  "Reproduce the deprecated install-time scheduling gap.

  The intended result is 30; the historical implementation returns nothing.
  This function remains callable for scheduler investigations but is not a
  test or release gate."
  []
  (let [built (build-three-layer-accessor-on-scheduled-net
               (build-nested-with-cons-scheduled 3 {:schedule-cons? true}))
        {:keys [net tasks coll0 head0 head1 head2 out]} built
        [n tasks] (seed-cells! net tasks [[head0 10] [head1 20] [head2 30]])
        n' (run-from n tasks [coll0])]
    (cell/cell-strongest (net/network-env-lookup n' out))))

(deftest defer-enqueue-until-after-seed-accessor-reaches-out
  (testing "enqueue cons prop ids only after seed — want out = 30"
    (let [{:keys [net cons-prop-ids head-ids coll-ids]}
          (build-nested-with-cons-scheduled 3 {:schedule-cons? :after-seed})
          [head0 head1 head2] head-ids
          [coll0 coll1 coll2] coll-ids
          out (new-node-id)
          [n0 t0] (install-cell! net tq/empty-queue out value/nothing value/nothing)
          [n1 t1] (install-prop! n0 t0 (cd/p:cdr coll1 coll0))
          [n2 t2] (install-prop! n1 t1 (cd/p:cdr coll2 coll1))
          [n3 t3] (install-prop! n2 t2 (cd/p:car out coll2))
          [n4 t4] (seed-cells! n3 t3 [[head0 10] [head1 20] [head2 30]])
          tasks (enqueue-prop-ids t4 cons-prop-ids)
          n' (run-from n4 tasks [coll0])]
      (is (= 30 (cell/cell-strongest (net/network-env-lookup n' out)))))))
