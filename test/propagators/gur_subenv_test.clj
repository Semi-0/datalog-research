(ns propagators.gur-subenv-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur.subenv :as subenv]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :refer [message message-id]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.cells.diff :as diff]))

;; test is too low level
;; this is wrong
(defn- strongest [n id]
  (net/network-cell-strongest n id))

(defn- run-props [n prop-ids]
  (core/run-tasks (tq/enqueue-all tq/empty-queue prop-ids) n))

(defn- child-net
  [scope local-id]
  (-> net/empty-net
      (nb/install-cell local-id)
      (subenv/extend-env scope)
      (subenv/bind :x local-id)))

(deftest eval-cell-star-routes-through-owner-cell-only
  (testing "a scoped ref updates the owner network cell, not a same-id parent cell"
    (let [scope [:scope :child]
          owner-id (ids/new-node-id)
          local-id (ids/new-node-id)
          parent-local-observer (ids/new-node-id)
          owner-observer (ids/new-node-id)
          child (child-net scope local-id)
          n0 (-> net/empty-net
                 (nb/install-cell owner-id)
                 (nb/install-cell local-id)
                 (nb/install-cell parent-local-observer)
                 (nb/install-cell owner-observer))
          [local-watch n1]
          ((prop/construct-propagator
            (fn [_inputs _outputs _network]
              [(message parent-local-observer :parent-local-ran)])
            [local-id]
            [parent-local-observer])
           n0)
          [owner-watch n2]
          ((prop/construct-propagator
            (fn [_inputs _outputs _network]
              [(message owner-observer :owner-ran)])
            [owner-id]
            [owner-observer])
           n1)
          [_tasks n3] (core/eval-cell owner-id (message owner-id child) n2)
          target (subenv/name-ref scope :x)
          [tasks n4] (core/eval-cell* (net/net-dict-or-empty n3)
                                      (message target 42)
                                      n3)
          n5 (core/run-tasks tasks n4)
          updated-child (strongest n5 owner-id)]
      (is (= [:dispatch/subenv owner-id local-id]
             (net/network-dict-entry n3 target)))
      (is (= 42 (net/network-cell-strongest updated-child local-id)))
      (is (= value/nothing (strongest n5 local-id)))
      (is (= value/nothing (strongest n5 parent-local-observer)))
      (is (= :owner-ran (strongest n5 owner-observer)))
      (is (some? local-watch))
      (is (some? owner-watch)))))

(deftest eval-cell-star-routes-from-one-subenv-to-another
  (testing "a child prop can emit a sibling-scoped message through the parent scheduler"
    (let [scope-a [:scope :a]
          scope-b [:scope :b]
          owner-a (ids/new-node-id)
          owner-b (ids/new-node-id)
          local-a (ids/new-node-id)
          local-b (ids/new-node-id)
          child-a0 (-> net/empty-net
                       (nb/install-cell local-a)
                       (subenv/extend-env scope-a)
                       (subenv/bind :x local-a))
          [_sender child-a]
          ((prop/construct-propagator
            (fn [_inputs _outputs network]
              [(message (subenv/cell-ref scope-b local-b)
                        (strongest network local-a))])
            [local-a]
            [])
           child-a0)
          child-b (-> net/empty-net
                      (nb/install-cell local-b)
                      (subenv/extend-env scope-b)
                      (subenv/bind :y local-b))
          parent0 (-> net/empty-net
                      (nb/install-cell owner-a)
                      (nb/install-cell owner-b))
          [_ parent1] (core/eval-cell owner-a (message owner-a child-a) parent0)
          [_ parent2] (core/eval-cell owner-b (message owner-b child-b) parent1)
          [runner-a parent3] ((subenv/p:run-subenv-frame owner-a []) parent2)
          [tasks parent4] (core/eval-cell* (net/net-dict-or-empty parent3)
                                           (message (subenv/cell-ref scope-a local-a)
                                                    42)
                                           parent3)
          parent5 (core/run-tasks tasks parent4)
          parent6 (run-props parent5 [runner-a])
          updated-b (strongest parent6 owner-b)]
      (is (= 42 (strongest updated-b local-b))))))

(deftest subenv-frame-projects-outputs-through-diff-path
  (testing "inner output changes are projected to the external output by diff cells"
    (let [owner-id (ids/new-node-id)
          out-id (ids/new-node-id)
          parent0 (-> net/empty-net
                      (nb/install-cell owner-id)
                      (nb/install-cell out-id))
          child0 (subenv/install-frame-boundary parent0
                                                (subenv/extend-env net/empty-net
                                                                   [:scope :frame])
                                                []
                                                [out-id])
          out-inner (net/lookup-inner-out child0 out-id)
          child1 (nb/seed-cell child0 out-inner 99)
          parent1 (nb/seed-cell parent0 owner-id child1)
          [runner-prop parent2] ((subenv/p:run-subenv-frame owner-id [out-id])
                                 parent1)
          diff-log (atom [])
          orig-diff diff/diff-internal-output-cells
          recording-diff
          (fn [network-from network-to external-outputs]
            (let [msgs (vec (orig-diff network-from network-to external-outputs))]
              (swap! diff-log conj {:external-outputs (vec external-outputs)
                                    :targets (mapv message-id msgs)})
              msgs))]
      (with-redefs [diff/diff-internal-output-cells recording-diff]
        (let [parent3 (run-props parent2 [runner-prop])]
          (is (= 99 (strongest parent3 out-id)))
          (is (= [[out-id]] (mapv :targets @diff-log)))
          (is (= [[out-id]] (mapv :external-outputs @diff-log))))))))

(deftest contextual-recursive-fibonacci
  (testing "the generalized apply/recur engine computes scalar recursion"
    (is (= 0 (:value (subenv/run-fib 0))))
    (is (= 1 (:value (subenv/run-fib 1))))
    (is (= 5 (:value (subenv/run-fib 5))))
    (is (= 8 (:value (subenv/run-fib 6)))))

  (testing "an applied frame accumulates applied closure facts in its frame net"
    (let [closure-id (ids/new-node-id)
          n-id (ids/new-node-id)
          out-id (ids/new-node-id)
          closure (subenv/fib-closure)
          n0 (-> net/empty-net
                 (nb/install-cell closure-id closure closure)
                 (nb/install-cell n-id 3 3)
                 (nb/install-cell out-id))
          [props n1] ((subenv/p:apply-closure closure-id [n-id] out-id) n0)
          n2 (run-props n1 props)
          frame-id (net/network-dict-entry n2
                                           (subenv/application-key closure-id
                                                                   [n-id]
                                                                   out-id))
          frame-net (strongest n2 frame-id)
          applied-keys (filter #(and (vector? %)
                                     (= :gur/applied (first %)))
                               (keys (net/net-dict-or-empty frame-net)))
          applied-closure-keys (filter #(and (vector? %)
                                             (= :gur/applied-closure (first %)))
                                       (keys (net/net-dict-or-empty frame-net)))]
      (is (= 2 (strongest n2 out-id)))
      (is (seq applied-keys))
      (is (seq applied-closure-keys)))))

(defn- list-slot
  [v slot-key]
  (cond
    (value/unusable? v)
    v

    (and (net/net? v) (obj/accessor-source-slot-present? v slot-key))
    (obj/accessor-source-slot-value v slot-key)

    (net/net? v)
    (or (obj/slot-value v slot-key) value/nothing)

    (map? v)
    (get v slot-key value/nothing)

    :else
    value/nothing))

(defn- list->vec
  ([v] (list->vec v 32))
  ([v limit]
   (loop [current v
          remaining limit
          acc []]
     (cond
       (zero? remaining)
       acc

       (subenv/empty-list? current)
       acc

       (value/unusable? current)
       acc

       :else
       (recur (list-slot current :cdr)
              (dec remaining)
              (conj acc (list-slot current :car)))))))

(defn- list->data
  [v]
  (if (subenv/list-node-value? v)
    (mapv list->data (list->vec v))
    v))

(defn- dispatch-route-value
  [network route]
  (case (first route)
    :dispatch/subenv
    (let [[_ owner-id local-id] route
          child-net (strongest network owner-id)]
      (strongest child-net local-id))

    :dispatch/subenv-ref
    (let [[_ owner-id target] route
          child-net (strongest network owner-id)]
      (dispatch-route-value child-net
                            (net/network-dict-entry child-net target)))

    :dispatch/local
    (strongest network (second route))))

(defn- nested-subenv-targets
  [network name]
  (->> (net/net-dict-or-empty network)
       (keep (fn [[k route]]
               (when (and (vector? k)
                          (= :env/ref (first k))
                          (= name (nth k 2 nil))
                          (vector? route)
                          (= :dispatch/subenv-ref (first route)))
                 [k route])))))

(deftest contextual-recursive-map-list-over-compound-data
  (testing "map-list uses the same contextual apply/recur engine over compound data"
    (let [{:keys [value]} (subenv/run-map-list-fib [0 1 2 3 4 5])]
      (is (= [0 1 1 2 3 5] (list->vec value))))))

(deftest contextual-recursive-nested-map-list-over-compound-data
  (testing "nested map-list composition maps inner compound/list elements"
    (let [source [(subenv/cons-list-value [0 1])
                  (subenv/cons-list-value [2 3])]
          {:keys [value]} (subenv/run-nested-map-list-fib source)]
      (is (= [[0 1] [1 2]] (list->data value))))))

(deftest nested-compound-recursion-dispatches-bidirectionally
  (testing "a parent message can route into a nested recursive frame, and output returns by diff"
    (let [fib-id (ids/new-node-id)
          map-id (ids/new-node-id)
          mapper-id (ids/new-node-id)
          list-id (ids/new-node-id)
          acc-id (ids/new-node-id)
          out-id (ids/new-node-id)
          source [(subenv/cons-cell-value 0 value/nothing)]
          n0 (-> net/empty-net
                 (nb/install-cell fib-id (subenv/fib-closure) (subenv/fib-closure))
                 (nb/install-cell map-id (subenv/map-list-closure) (subenv/map-list-closure))
                 (nb/install-cell mapper-id
                                  (subenv/map-list-fib-closure)
                                  (subenv/map-list-fib-closure))
                 (nb/install-cell list-id (subenv/cons-list-value source) (subenv/cons-list-value source))
                 (nb/install-cell acc-id subenv/empty-list subenv/empty-list)
                 (nb/install-cell out-id))
          [props n1] ((subenv/p:apply-closure map-id [list-id mapper-id acc-id] out-id)
                      n0)
          n2 (run-props n1 props)
          nested-rest-target
          (some (fn [[target route]]
                  (when (value/nothing? (dispatch-route-value n2 route))
                    target))
                (nested-subenv-targets n2 :rest))
          diff-log (atom [])
          orig-diff diff/diff-internal-output-cells
          recording-diff
          (fn [network-from network-to external-outputs]
            (let [msgs (vec (orig-diff network-from network-to external-outputs))]
              (swap! diff-log conj {:external-outputs (vec external-outputs)
                                    :targets (mapv message-id msgs)})
              msgs))]
      (is (= [[0]] (list->data (strongest n2 out-id))))
      (is (some? nested-rest-target))
      (is (= :dispatch/subenv-ref
             (first (net/network-dict-entry n2 nested-rest-target))))
      (with-redefs [diff/diff-internal-output-cells recording-diff]
        (let [[tasks n3] (core/eval-cell* (net/net-dict-or-empty n2)
                                          (message nested-rest-target
                                                   (subenv/cons-list-value [1 2]))
                                          n2)
              n4 (core/run-tasks tasks n3)]
          (is (= [[0 1 1]] (list->data (strongest n4 out-id))))
          (is (some (fn [{:keys [targets]}]
                      (some #{out-id} targets))
                    @diff-log)))))))

(deftest late-cdr-delivery-enters-frame-and-projects-output-through-diff
  (testing "late cdr delivery updates the child frame via scoped dispatch and wakes the diff runner"
    (let [fib-id (ids/new-node-id)
          map-id (ids/new-node-id)
          list-id (ids/new-node-id)
          acc-id (ids/new-node-id)
          out-id (ids/new-node-id)
          initial-list (subenv/cons-cell-value 0 value/nothing)
          n0 (-> net/empty-net
                 (nb/install-cell fib-id (subenv/fib-closure) (subenv/fib-closure))
                 (nb/install-cell map-id (subenv/map-list-closure) (subenv/map-list-closure))
                 (nb/install-cell list-id initial-list initial-list)
                 (nb/install-cell acc-id subenv/empty-list subenv/empty-list)
                 (nb/install-cell out-id))
          [props n1] ((subenv/p:apply-closure map-id [list-id fib-id acc-id] out-id)
                      n0)
          n2 (run-props n1 props)
          frame-id (net/network-dict-entry n2
                                           (subenv/application-key map-id
                                                                   [list-id fib-id acc-id]
                                                                   out-id))
          frame-net (strongest n2 frame-id)
          scope (net/network-dict-entry frame-net subenv/scope-key)
          target (subenv/name-ref scope :rest)
          late-rest (subenv/cons-list-value [1 2])
          diff-log (atom [])
          orig-diff diff/diff-internal-output-cells
          recording-diff
          (fn [network-from network-to external-outputs]
            (let [msgs (vec (orig-diff network-from network-to external-outputs))]
              (swap! diff-log conj {:external-outputs (vec external-outputs)
                                    :targets (mapv message-id msgs)})
              msgs))]
      (is (= value/nothing
             (list-slot (list-slot (strongest n2 out-id) :cdr) :car)))
      (is (= [:dispatch/subenv frame-id
              (second (first (filter #(= :rest (first %))
                                     (subenv/bindings frame-net))))]
             (net/network-dict-entry n2 target)))
      (with-redefs [diff/diff-internal-output-cells recording-diff]
        (let [[tasks n3] (core/eval-cell* (net/net-dict-or-empty n2)
                                          (message target late-rest)
                                          n2)
              n4 (core/run-tasks tasks n3)]
          (is (= [0 1 1] (list->vec (strongest n4 out-id))))
          (is (some (fn [{:keys [targets]}]
                      (some #{out-id} targets))
                    @diff-log)))))))
