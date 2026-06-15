(ns propagators.gur-routed-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur-routed :as gur-routed]
            [propagators.ids :as ids]
            [propagators.message :as message]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as propagator]
            [propagators.reality :as reality]
            [propagators.recursive :as recursive]
            [propagators.recursive-compound-test :as recursive-test]
            [propagators.stdlib.prop :as prop]))

(defn- strongest
  [n id]
  (net/network-cell-strongest n id))

(defn- run-installed
  [network prop-ids]
  (nb/run-propagators network (vec prop-ids)))

(defn- inc-recursive-closure
  []
  (recursive/recursive-closure
   (fn [{:keys [input-ids output-ids network]}]
     (let [source-id (first input-ids)
           out-id (first output-ids)
           v (strongest network source-id)]
       {:network (if (value/unusable? v)
                   network
                   (nb/seed-cell network out-id (inc v)))}))))

(defn- child-forward-frame
  []
  (let [in-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell in-id)
               (nb/install-cell out-id))
        [id-prop n1] ((prop/id in-id out-id) n0)
        {frame :net boundary-props :prop-ids}
        (gur-routed/install-boundary n1
                                     {:inputs [[:in in-id]]
                                      :outputs [[:out out-id]]})]
    {:net (net/update-net-dict-entry frame
                                     gur-routed/prop-ids-key
                                     #(into (vec (or % []))
                                            (into [id-prop]
                                                  boundary-props)))
     :in in-id
     :out out-id}))

(defn- topology-emitter-frame
  [declaration]
  (let [topology-id (ids/new-node-id)
        n0 (nb/install-cell net/empty-net topology-id)
        [emit-prop n1]
        ((propagator/construct-propagator
          (fn [_inputs _outputs _network]
            [(message/message topology-id declaration)])
          []
          [topology-id])
         n0)
        {frame :net boundary-props :prop-ids}
        (gur-routed/install-boundary n1
                                     {:inputs []
                                      :outputs [[gur-routed/default-topology-io-id
                                                 topology-id]]})]
    (net/update-net-dict-entry frame
                               gur-routed/prop-ids-key
                               #(into (vec (or % []))
                                      (into [emit-prop]
                                            boundary-props)))))

(defn- install-cons-list
  [network values]
  (let [heads (mapv (fn [v] [(ids/new-node-id) v]) values)
        colls (mapv (fn [_] (ids/new-node-id)) values)
        sentinel (ids/new-node-id)
        n0 (reduce (fn [n [id v]]
                     (nb/install-cell n id v v))
                   (reduce nb/install-cell
                           (nb/install-cell network
                                            sentinel
                                            (obj/empty-compound-object)
                                            (obj/empty-compound-object))
                           colls)
                   heads)]
    (loop [n n0
           i 0
           props []]
      (if (= i (count values))
        {:net n
         :props props
         :root (first colls)
         :sentinel sentinel
         :heads (mapv first heads)
         :colls colls}
        (let [head-id (first (nth heads i))
              coll-id (nth colls i)
              tail-id (if (= i (dec (count values)))
                        sentinel
                        (nth colls (inc i)))
              [[car-prop cdr-prop] n1] ((obj/p:cons head-id tail-id coll-id) n)]
          (recur n1
                 (inc i)
                 (into props [car-prop cdr-prop])))))))

(defn- install-list-readers
  [network root length]
  (let [cars (mapv (fn [_] (ids/new-node-id)) (range length))
        tails (mapv (fn [_] (ids/new-node-id)) (range length))
        n0 (reduce nb/install-cell network (into cars tails))]
    (loop [n n0
           i 0
           coll root
           props []]
      (if (= i length)
        {:net n
         :props props
         :cars cars
         :tails tails
         :tail (last tails)}
        (let [car-id (nth cars i)
              tail-id (nth tails i)
              [car-prop n1] ((obj/p:car car-id coll) n)
              [cdr-prop n2] ((obj/p:cdr tail-id coll) n1)]
          (recur n2
                 (inc i)
                 tail-id
                 (into props [car-prop cdr-prop])))))))

(defn- install-tail-cons
  [network tail-id value]
  (let [head-id (ids/new-node-id)
        next-tail-id (ids/new-node-id)
        n0 (-> network
               (nb/install-cell head-id value value)
               (nb/install-cell next-tail-id
                                (obj/empty-compound-object)
                                (obj/empty-compound-object)))
        [[car-prop cdr-prop] n1] ((obj/p:cons head-id next-tail-id tail-id) n0)]
    {:net n1
     :props [car-prop cdr-prop]
     :head-id head-id
     :tail-id next-tail-id}))

(defn- install-car-writer
  [network coll-id]
  (let [writer-id (ids/new-node-id)
        n0 (nb/install-cell network writer-id)
        [prop-id n1] ((obj/p:car writer-id coll-id) n0)]
    {:net n1
     :prop prop-id
     :writer-id writer-id}))

(defn- run-routed-map
  [values closure-value reader-length]
  (let [closure-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure-value closure-value)
               (nb/install-cell acc-id)
               (nb/install-cell out-id))
        {n1 :net
         input-props :props
         source-id :root
         source-colls :colls
         source-sentinel :sentinel}
        (install-cons-list n0 values)
        [map-props n2] ((gur-routed/p:routed-accessor-recursive-map
                         closure-id
                         acc-id
                         source-id
                         out-id)
                        n1)
        {n3 :net reader-props :props car-ids :cars tail-id :tail}
        (install-list-readers n2 out-id reader-length)
        n4 (run-installed n3 (into (vec input-props)
                                   (into (vec map-props)
                                         reader-props)))]
    {:net n4
     :closure-id closure-id
     :acc-id acc-id
     :out-id out-id
     :source-id source-id
     :source-colls source-colls
     :source-sentinel source-sentinel
     :car-ids car-ids
     :tail-id tail-id
     :values (mapv #(strongest n4 %) car-ids)
     :tail-value (strongest n4 tail-id)}))

(deftest routed-frame-runs-through-reality-io
  (let [{frame :net child-in :in} (child-forward-frame)
        frame-id (ids/new-node-id)
        parent-in (ids/new-node-id)
        parent-out (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell frame-id frame frame)
               (nb/install-cell parent-in 7 7)
               (nb/install-cell parent-out))
        [runner-prop n1] ((gur-routed/p:routed-run-frame
                           frame-id
                           [[:in parent-in child-in]]
                           [[:out parent-out]]
                           gur-routed/default-topology-io-id)
                          n0)
        n2 (run-installed n1 [runner-prop])
        frame2 (strongest n2 frame-id)]
    (is (= 7 (strongest n2 parent-out)))
    (is (= 7 (net/network-cell-strongest frame2 child-in)))
    (is (empty? (:outbox (net/net-io frame2))))))

(deftest topology-output-installs-parent-topology-once
  (let [parent-in (ids/new-node-id)
        parent-out (ids/new-node-id)
        declaration (gur-routed/topology-declaration
                     [:test/install-id parent-in parent-out]
                     :install-id
                     {:from parent-in :to parent-out})
        declaration->installer
        (fn [decl]
          {:id (get decl gur-routed/declaration-id-key)
           :installed-key gur-routed/installed-declarations-key
           :installer
           (fn [network]
             (let [{:keys [from to]} (get decl gur-routed/declaration-args-key)
                   [prop-id n1] ((prop/id from to) network)]
               {:net n1
                :prop-ids [prop-id]}))})
        frame (topology-emitter-frame declaration)
        frame-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell frame-id frame frame)
               (nb/install-cell parent-in 9 9)
               (nb/install-cell parent-out))
        [runner-prop n1] ((gur-routed/p:routed-run-frame
                           frame-id
                           []
                           []
                           gur-routed/default-topology-io-id
                           declaration->installer)
                          n0)
        n2 (run-installed n1 [runner-prop])
        installed1 (net/network-dict-entry n2
                                           gur-routed/installed-declarations-key)
        n3 (run-installed n2 [runner-prop])
        installed2 (net/network-dict-entry n3
                                           gur-routed/installed-declarations-key)]
    (is (= 9 (strongest n2 parent-out)))
    (is (= installed1 installed2))
    (is (= #{[:test/install-id parent-in parent-out]} installed2))))

(deftest routed-accessor-map-over-prebuilt-cons-list
  (let [{:keys [values tail-value]}
        (run-routed-map [1 2 3] (inc-recursive-closure) 3)]
    (is (= [2 3 4] values))
    (is (obj/accessor-network? (obj/as-accessor-network tail-value)))))

(deftest routed-accessor-map-supports-fib-style-leaf-recursion
  (let [closure-value ((var-get #'recursive-test/fib-frame-closure)
                       :accumulating
                       {})
        {:keys [values acc-id net]}
        (run-routed-map [0 1 2 3] closure-value 4)]
    (is (= [0 1 1 2] values))
    (is (net/network? (strongest net acc-id)))))

(deftest routed-accessor-map-expands-late-cdr-through-public-accessor
  (let [{:keys [net car-ids source-sentinel tail-id]}
        (run-routed-map [1] (inc-recursive-closure) 2)
        second-car (second car-ids)
        source-tail source-sentinel]
    (is (= 2 (strongest net (first car-ids))))
    (is (value/nothing? (strongest net second-car)))
    (let [{n1 :net late-props :props} (install-tail-cons net source-tail 4)
          n2 (run-installed n1 late-props)]
      (is (= 5 (strongest n2 second-car)))
      (is (obj/accessor-network? (obj/as-accessor-network
                                  (strongest n2 tail-id)))))))

(deftest routed-accessor-map-recurses-into-nested-cons-car
  (let [closure-value (inc-recursive-closure)
        closure-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure-value closure-value)
               (nb/install-cell acc-id)
               (nb/install-cell out-id))
        {n1 :net inner-props :props inner-root :root}
        (install-cons-list n0 [1 2])
        outer-head-id (ids/new-node-id)
        outer-root-id (ids/new-node-id)
        outer-tail-id (ids/new-node-id)
        outer-sentinel-id (ids/new-node-id)
        n2 (-> n1
               (nb/install-cell outer-head-id 3 3)
               (nb/install-cell outer-root-id)
               (nb/install-cell outer-tail-id)
               (nb/install-cell outer-sentinel-id
                                (obj/empty-compound-object)
                                (obj/empty-compound-object)))
        [[outer-car-prop outer-cdr-prop] n3]
        ((obj/p:cons inner-root outer-tail-id outer-root-id) n2)
        [[tail-car-prop tail-cdr-prop] n4]
        ((obj/p:cons outer-head-id outer-sentinel-id outer-tail-id) n3)
        [map-props n5] ((gur-routed/p:routed-accessor-recursive-map
                         closure-id
                         acc-id
                         outer-root-id
                         out-id)
                        n4)
        {n6 :net outer-reader-props :props outer-cars :cars}
        (install-list-readers n5 out-id 2)
        n7 (run-installed n6
                          (into (vec inner-props)
                                (into [outer-car-prop outer-cdr-prop
                                       tail-car-prop tail-cdr-prop]
                                      (into (vec map-props)
                                            outer-reader-props))))
        nested-out-id (first outer-cars)
        nested-reader (install-list-readers n7 nested-out-id 2)
        n8 (run-installed (:net nested-reader) (:props nested-reader))]
    (is (obj/accessor-network? (obj/as-accessor-network
                                (strongest n8 nested-out-id))))
    (is (= [2 3] (mapv #(strongest n8 %) (:cars nested-reader))))
    (is (= 4 (strongest n8 (second outer-cars))))))

(defn- run-with-prop-count
  [network prop-ids]
  (let [counter (atom 0)
        original (var-get #'core/eval-propagator)]
    (with-redefs [core/eval-propagator
                  (fn [current-id tasks n]
                    (swap! counter inc)
                    (original current-id tasks n))]
      [(run-installed network prop-ids) @counter])))

(defn- update-source-car-through-public-accessor
  [network coll-id value]
  (let [{:keys [net prop writer-id]} (install-car-writer network coll-id)
        n1 (run-installed net [prop])
        n2 (nb/seed-cell n1 writer-id value)]
    (run-with-prop-count n2 [prop])))

(deftest routed-accessor-map-incremental-update-count-is-depth-stable
  (let [values (vec (repeat 70 value/nothing))
        {:keys [net source-colls car-ids]}
        (run-routed-map values (inc-recursive-closure) 70)
        [n10 count10] (update-source-car-through-public-accessor
                       net
                       (nth source-colls 10)
                       100)
        [_n60 count60] (update-source-car-through-public-accessor
                        net
                        (nth source-colls 60)
                        100)]
    (is (= 101 (strongest n10 (nth car-ids 10))))
    (is (= 101 (strongest _n60 (nth car-ids 60))))
    (is (<= (abs (- count10 count60)) 20)
        (str "expected similar incremental cost, got index10="
             count10
             " index60="
             count60))))
