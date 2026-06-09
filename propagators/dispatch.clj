(ns propagators.dispatch
  "Reusable dispatch topology helpers.

  These helpers are intentionally combinators over propagator installers. They do
  not dispatch on policy tags at runtime; callers choose the reducer topology
  when constructing an application network."
  (:require [propagators.cells.value :as value]
            [propagators.closure :as closure]
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
   (prop/concrete-propagator
    (fn [_inputs _outputs network]
     (let [pred (net/network-cell-strongest network pred-id)
           v (net/network-cell-strongest network in-id)]
       (if pred [(message out-id v)] []))))
   [pred-id in-id]
   [out-id]))

(declare p:match-args* p:matched-handler-branch*)

(defn p:match-args
  "Run one predicate closure per arg, then apply `matcher-id` to the predicate
  results and write the match decision to `match-out-id`."
  [predicate-ids matcher-id arg-ids match-out-id]
  (:installer (p:match-args* predicate-ids matcher-id arg-ids match-out-id)))

(defn p:match-args*
  "Debuggable `p:match-args`; returns ids for predicate result cells."
  [predicate-ids matcher-id arg-ids match-out-id]
  (let [predicate-out-ids (vec (repeatedly (count arg-ids) ids/new-node-id))]
    {:predicate-out-ids predicate-out-ids
     :installer
     (fn [n]
       (when-not (= (count predicate-ids) (count arg-ids))
         (throw (ex-info "predicate/arg arity mismatch"
                         {:predicate-count (count predicate-ids)
                          :arg-count (count arg-ids)})))
       (let [n0 (reduce nb/install-cell n predicate-out-ids)
             [predicate-props n1]
             (reduce
              (fn [[prop-ids acc] [predicate-id arg-id predicate-out-id]]
                (let [[prop-id acc']
                      ((closure/p:apply-closure predicate-id
                                                arg-id
                                                predicate-out-id)
                       acc)]
                  [(conj prop-ids prop-id) acc']))
              [[] n0]
              (map vector predicate-ids arg-ids predicate-out-ids))
             [matcher-prop n2]
             ((apply closure/p:apply-closure matcher-id
                     (conj predicate-out-ids match-out-id))
              n1)]
        [(conj predicate-props matcher-prop) n2]))}))

(defn p:filter-args
  "Forward each arg to its paired output when `match-id` is usable and truthy."
  [match-id arg-ids out-ids]
  (fn [n]
    (when-not (= (count arg-ids) (count out-ids))
      (throw (ex-info "arg/filter-output arity mismatch"
                      {:arg-count (count arg-ids)
                       :out-count (count out-ids)})))
    (reduce
     (fn [[prop-ids acc] [arg-id out-id]]
       (let [[prop-id acc'] ((p:filter match-id arg-id out-id) acc)]
         [(conj prop-ids prop-id) acc']))
     [[] n]
     (map vector arg-ids out-ids))))

(defn p:matched-handler-branch
  "Install a reusable dispatch branch.

  Predicates and matcher decide whether the arg tuple applies. Matching args are
  forwarded into `handler-id`; the handler result is written to `result-bank-id`
  under `slot-key`."
  [slot-key predicate-ids matcher-id handler-id arg-ids result-bank-id]
  (:installer (p:matched-handler-branch* slot-key
                                         predicate-ids
                                         matcher-id
                                         handler-id
                                         arg-ids
                                         result-bank-id)))

(defn p:matched-handler-branch*
  "Debuggable `p:matched-handler-branch`; returns intermediate cell ids."
  [slot-key predicate-ids matcher-id handler-id arg-ids result-bank-id]
  (let [match-out-id (ids/new-node-id)
        handler-out-id (ids/new-node-id)
        filtered-ids (vec (repeatedly (count arg-ids) ids/new-node-id))
        match-args (p:match-args* predicate-ids matcher-id arg-ids match-out-id)]
    {:slot-key slot-key
     :predicate-out-ids (:predicate-out-ids match-args)
     :match-out-id match-out-id
     :filtered-ids filtered-ids
     :handler-out-id handler-out-id
     :result-bank-id result-bank-id
     :installer
     (fn [n]
       (let [n0 (reduce nb/install-cell
                        n
                        (into [match-out-id handler-out-id] filtered-ids))
             [match-props n1] ((:installer match-args) n0)
             [filter-props n2] ((p:filter-args match-out-id arg-ids filtered-ids) n1)
             [handler-prop n3] ((apply closure/p:apply-closure handler-id
                                       (conj filtered-ids handler-out-id))
                                n2)
             [slot-prop n4] ((obj/p:slot slot-key handler-out-id result-bank-id) n3)]
         [(into (vec match-props) (concat filter-props [handler-prop slot-prop]))
          n4]))}))

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
      (if (value/contradiction? default-value)
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
