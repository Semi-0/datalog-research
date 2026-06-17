(ns propagators.gur-subenv-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur.subenv :as subenv]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

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

(defn- observe-first-two-heads
  [network collection-id]
  (let [head0-id (ids/new-node-id)
        tail0-id (ids/new-node-id)
        head1-id (ids/new-node-id)
        tail1-id (ids/new-node-id)
        n0 (reduce nb/install-cell
                   network
                   [head0-id tail0-id head1-id tail1-id])
        [head0-prop n1] ((obj/p:car head0-id collection-id) n0)
        [tail0-prop n2] ((obj/p:cdr tail0-id collection-id) n1)
        [head1-prop n3] ((obj/p:car head1-id tail0-id) n2)
        [tail1-prop n4] ((obj/p:cdr tail1-id tail0-id) n3)
        n5 (run-props n4 [head0-prop tail0-prop head1-prop tail1-prop])]
    {:net n5
     :heads [(strongest n5 head0-id)
             (strongest n5 head1-id)]}))

(defn- observe-first-inner-heads
  [network collection-id]
  (let [inner-id (ids/new-node-id)
        outer-tail-id (ids/new-node-id)
        inner-head0-id (ids/new-node-id)
        inner-tail0-id (ids/new-node-id)
        inner-head1-id (ids/new-node-id)
        inner-tail1-id (ids/new-node-id)
        n0 (reduce nb/install-cell
                   network
                   [inner-id outer-tail-id inner-head0-id inner-tail0-id
                    inner-head1-id inner-tail1-id])
        [outer-head-prop n1] ((obj/p:car inner-id collection-id) n0)
        [outer-tail-prop n2] ((obj/p:cdr outer-tail-id collection-id) n1)
        [inner-head0-prop n3] ((obj/p:car inner-head0-id inner-id) n2)
        [inner-tail0-prop n4] ((obj/p:cdr inner-tail0-id inner-id) n3)
        [inner-head1-prop n5] ((obj/p:car inner-head1-id inner-tail0-id) n4)
        [inner-tail1-prop n6] ((obj/p:cdr inner-tail1-id inner-tail0-id) n5)
        n7 (run-props n6 [outer-head-prop outer-tail-prop
                          inner-head0-prop inner-tail0-prop
                          inner-head1-prop inner-tail1-prop])]
    {:net n7
     :heads [(strongest n7 inner-head0-id)
             (strongest n7 inner-head1-id)]}))

(defn- constructed-accessor-map-probe
  []
  (let [fib-id (ids/new-node-id)
        map-id (ids/new-node-id)
        list-id (ids/new-node-id)
        head0-id (ids/new-node-id)
        tail0-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell fib-id (subenv/fib-closure) (subenv/fib-closure))
               (nb/install-cell map-id (subenv/map-list-closure) (subenv/map-list-closure))
               (nb/install-cell list-id)
               (nb/install-cell head0-id 0 0)
               (nb/install-cell tail0-id)
               (nb/install-cell acc-id subenv/empty-list subenv/empty-list)
               (nb/install-cell out-id))
        [[car0-prop cdr0-prop] n1] ((obj/p:cons head0-id tail0-id list-id) n0)
        [map-props n2] ((subenv/p:apply-closure map-id
                                               [list-id fib-id acc-id]
                                               out-id)
                        n1)
        n3 (run-props n2 (concat [car0-prop cdr0-prop] map-props))
        source-cell-before (strongest n3 list-id)
        mapped-cell-before (strongest n3 out-id)
        {n3a :net parent-before :heads} (observe-first-two-heads n3 list-id)
        {n3b :net mapped-before :heads} (observe-first-two-heads n3a out-id)
        source-cell-after-observers (strongest n3b list-id)
        mapped-cell-after-observers (strongest n3b out-id)
        head1-id (ids/new-node-id)
        tail1-id (ids/new-node-id)
        n4 (-> n3b
               (nb/install-cell head1-id 1 1)
               (nb/install-cell tail1-id))
        [[car1-prop cdr1-prop] n5] ((obj/p:cons head1-id tail1-id tail0-id) n4)
        n6 (run-props n5 [car1-prop cdr1-prop])
        source-cell-after (strongest n6 list-id)
        mapped-cell-after (strongest n6 out-id)
        {n6a :net parent-after :heads} (observe-first-two-heads n6 list-id)
        {mapped-after :heads} (observe-first-two-heads n6a out-id)]
    {:parent-before parent-before
     :parent-after parent-after
     :mapped-before mapped-before
     :mapped-after mapped-after
     :source-cell-observer-changed? (not= source-cell-before
                                          source-cell-after-observers)
     :source-cell-extension-changed? (not= source-cell-after-observers
                                           source-cell-after)
     :mapped-cell-observer-changed? (not= mapped-cell-before
                                          mapped-cell-after-observers)
     :mapped-cell-extension-changed? (not= mapped-cell-after-observers
                                           mapped-cell-after)
     :source-cell-cdr-before (list-slot source-cell-before :cdr)
     :source-cell-cdr-after-observers (list-slot source-cell-after-observers
                                                 :cdr)
     :source-cell-cdr-after-extension (list-slot source-cell-after :cdr)}))

(defn- constructed-nested-accessor-map-probe
  []
  (let [map-id (ids/new-node-id)
        mapper-id (ids/new-node-id)
        outer-id (ids/new-node-id)
        inner-id (ids/new-node-id)
        inner-head0-id (ids/new-node-id)
        inner-tail0-id (ids/new-node-id)
        outer-tail-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell map-id (subenv/map-list-closure) (subenv/map-list-closure))
               (nb/install-cell mapper-id
                                (subenv/map-list-fib-closure)
                                (subenv/map-list-fib-closure))
               (nb/install-cell outer-id)
               (nb/install-cell inner-id)
               (nb/install-cell inner-head0-id 0 0)
               (nb/install-cell inner-tail0-id)
               (nb/install-cell outer-tail-id subenv/empty-list subenv/empty-list)
               (nb/install-cell acc-id subenv/empty-list subenv/empty-list)
               (nb/install-cell out-id))
        [[inner-car0-prop inner-cdr0-prop] n1]
        ((obj/p:cons inner-head0-id inner-tail0-id inner-id) n0)
        [[outer-car0-prop outer-cdr0-prop] n2]
        ((obj/p:cons inner-id outer-tail-id outer-id) n1)
        [map-props n3]
        ((subenv/p:apply-closure map-id [outer-id mapper-id acc-id] out-id) n2)
        n4 (run-props n3 (concat [inner-car0-prop inner-cdr0-prop
                                  outer-car0-prop outer-cdr0-prop]
                                 map-props))
        {n4a :net before :heads} (observe-first-inner-heads n4 out-id)
        inner-head1-id (ids/new-node-id)
        inner-tail1-id (ids/new-node-id)
        n5 (-> n4a
               (nb/install-cell inner-head1-id 1 1)
               (nb/install-cell inner-tail1-id subenv/empty-list subenv/empty-list))
        [[inner-car1-prop inner-cdr1-prop] n6]
        ((obj/p:cons inner-head1-id inner-tail1-id inner-tail0-id) n5)
        n7 (run-props n6 [inner-car1-prop inner-cdr1-prop])
        {after :heads} (observe-first-inner-heads n7 out-id)]
    {:before before
     :after after}))

(deftest contextual-recursive-map-list-over-compound-data
  (testing "map-list uses the same contextual apply/recur engine over compound data"
    (let [{:keys [value]} (subenv/run-map-list-fib [])]
      (is (= [] (list->vec value))))
    (let [{:keys [value]} (subenv/run-map-list-fib [0 1 2 3 4 5])]
      (is (= [0 1 1 2 3 5] (list->vec value))))))

(deftest constructed-accessor-map-lazy-extension-routes-through-scoped-slot-dispatch
  (testing "accessor observers and recursive map output both see the lazy extension"
    (let [{:keys [parent-before parent-after mapped-before mapped-after
                  source-cell-observer-changed?
                  mapped-cell-observer-changed?
                  source-cell-cdr-before
                  source-cell-cdr-after-observers
                  source-cell-cdr-after-extension]}
          (constructed-accessor-map-probe)]
      (is (= [0 value/nothing] parent-before))
      (is (= [0 1] parent-after))
      (is (true? source-cell-observer-changed?)
          "Installing parent accessors does rewrite the source collection cell with accessor route topology.")
      (is (= value/nothing source-cell-cdr-before))
      (is (= value/nothing source-cell-cdr-after-observers))
      (is (= value/nothing source-cell-cdr-after-extension)
          "The source cdr value update remains route-observable, not materialized as a direct source slot.")
      (is (true? mapped-cell-observer-changed?)
          "Installing parent accessors also rewrites the mapped output cell with accessor route topology.")
      (is (= 0 (first mapped-before)))
      (is (nil? (second mapped-before))
          "Before the lazy extension there is no second mapped output element.")
      (is (= [0 1] mapped-after)
          "The source cdr update routes through the child scoped slot accessor and wakes the recursive frame."))))

(deftest contextual-recursive-nested-map-list-over-compound-data
  (testing "nested map-list composition maps inner compound/list elements"
    (let [source [(subenv/cons-list-value [0 1])
                  (subenv/cons-list-value [2 3])]
          {:keys [value]} (subenv/run-nested-map-list-fib source)]
      (is (= [[0 1] [1 2]] (list->data value))))))

(deftest constructed-nested-accessor-map-lazy-extension-routes-transitively
  (testing "nested constructed inner cdr extension reaches the nested mapper frame"
    (let [{:keys [before after]} (constructed-nested-accessor-map-probe)]
      (is (= 0 (first before)))
      (is (contains? #{nil value/nothing} (second before)))
      (is (= [0 1] after)))))

(deftest nested-map-dispatches-to-child-and-projects-output
  (testing "nested map composition should route a parent message into the child frame and update only through recursive output"
    (let [map-id (ids/new-node-id)
          mapper-id (ids/new-node-id)
          list-id (ids/new-node-id)
          acc-id (ids/new-node-id)
          out-id (ids/new-node-id)
          source [(subenv/cons-cell-value 0 value/nothing)]
          n0 (-> net/empty-net
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
          late-rest (subenv/cons-list-value [1 2])]
      (is (= [[0]] (list->data (strongest n2 out-id))))
      (is (some? nested-rest-target))
      (is (= :dispatch/subenv-ref
             (first (net/network-dict-entry n2 nested-rest-target))))
      (let [[tasks n3] (core/eval-cell* (net/net-dict-or-empty n2)
                                        (message nested-rest-target late-rest)
                                        n2)
            n4 (core/run-tasks tasks n3)]
        (is (= [[0 1 1]] (list->data (strongest n4 out-id))))))))
