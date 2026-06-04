(ns propagators.dispatch
  "Reusable dispatch topology helpers.

  These helpers are intentionally combinators over propagator installers. They do
  not dispatch on policy tags at runtime; callers choose the reducer topology
  when constructing an application network."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn p:filter
  "Forward `in-id` to `out-id` when `pred-id` has a usable truthy value."
  [pred-id in-id out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [pred (net/network-cell-strongest network pred-id)
           v (net/network-cell-strongest network in-id)]
       (cond
         (or (value/unusable? pred) (value/unusable? v)) []
         pred [(message out-id v)]
         :else [])))
   [pred-id in-id]
   [out-id]))

(defn install-result-bank
  "Install an empty compound-object result bank cell."
  [n result-bank-id]
  (nb/install-cell n
                   result-bank-id
                   (net/net-with-dict net/empty-net {:slot-index {}})
                   (net/net-with-dict net/empty-net {:slot-index {}})))

(defn reduce-results
  "Return an installer that wires `result-bank-id` to `out-id` with
  `policy-install`.

  `policy-install` is a function of `[network result-bank-id out-id]` returning
  `[prop-ids network']`."
  [policy-install result-bank-id out-id]
  (fn [n]
    (policy-install n result-bank-id out-id)))

(defn- reducer-merge-net
  [f]
  (let [acc-id (ids/new-node-id)
        update-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell acc-id)
               (nb/install-cell update-id)
               (nb/install-cell out-id))
        [_ n1] (((prop/primitive-propagator f) acc-id update-id out-id) n0)]
    (-> n1
        (net/assoc-net-dict-entry :acc acc-id)
        (net/assoc-net-dict-entry :update update-id)
        (net/assoc-net-dict-entry :out out-id))))

(defn- install-reducer
  [n source-id merge-net init out-id]
  (let [merge-net-id (ids/new-node-id)
        init-id (ids/new-node-id)
        n0 (-> n
               (nb/install-cell merge-net-id merge-net merge-net)
               (nb/install-cell init-id init init))]
    (let [[reduce-prop-id n1] ((obj/p:reduce source-id merge-net-id init-id out-id) n0)]
      [[reduce-prop-id] n1])))

(defn- empty-layered-object
  []
  (net/net-with-dict net/empty-net {:slot-index {}}))

(defn- assoc-layer-value
  [layered-object slot-key slot-value]
  (let [slot-id (or (net/network-dict-entry layered-object slot-key)
                    (ids/new-node-id))
        n0 (if (contains? (net/net-env layered-object) slot-id)
             layered-object
             (nb/install-cell layered-object slot-id))]
    (-> n0
        (nb/seed-cell slot-id slot-value)
        (net/assoc-net-dict-entry slot-key slot-id))))

(defn layered-object-policy
  "Reducer combinator that copies each result-bank slot to the same output slot."
  [_slot-keys]
  (fn [n result-bank-id out-id]
    (install-reducer
     n
     result-bank-id
     (reducer-merge-net
      (fn [acc {:keys [slot value]}]
        (assoc-layer-value acc slot value)))
     (empty-layered-object)
     out-id)))

(defn select-one-policy
  "Reducer combinator for generic-procedure prototypes.

  Emits exactly one usable result from `ordered-slot-keys`, falls back to
  `default-id` when none are usable, and emits contradiction when multiple
  results are usable."
  [ordered-slot-keys default-id]
  (fn [n result-bank-id out-id]
    (let [default-value (net/network-cell-strongest n default-id)]
      (if (value/unusable? default-value)
        [[] n]
        (install-reducer
         n
         result-bank-id
         (reducer-merge-net
          (fn [acc {:keys [value]}]
            (if (= acc default-value)
              value
              value/contradiction)))
         default-value
         out-id)))))
