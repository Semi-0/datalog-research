(ns propagators.datastructures.compound_data
  "Linked-list propagators over compound subnet cells."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound_strongest_result :as strongest]
            [propagators.datastructures.compound_subnet_state :as state]
            [propagators.datastructures.compound_update :as update]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- avatar-strongest-message [subnet network outer-id]
  (when (get (net/net-dict-or-empty subnet) outer-id)
    (let [avatar-id (get (net/net-dict-or-empty subnet) outer-id)
          outer-content (net/network-cell-content network outer-id)]
      (if (state/compound-subnet-state? outer-content)
        (message outer-id (update/compound-sync (state/state-subnet outer-content) [outer-id]))
        (message outer-id (net/network-cell-strongest subnet avatar-id))))))

(defn p:car
  "Write `{:head elem-id}` compound-data update to collection."
  [elem-id collection-id]
  (prop/construct-propagator
   (fn [_inputs _outputs _network]
     [(message collection-id (update/compound-update {:head elem-id}))])
   [elem-id]
   [collection-id]))

(defn p:cdr
  "Write `{:tail elem-id}` compound-data update to collection."
  [elem-id collection-id]
  (prop/construct-propagator
   (fn [_inputs _outputs _network]
     [(message collection-id (update/compound-update {:tail elem-id}))])
   [elem-id]
   [collection-id]))

(defn c:linked-list
  "Constraint: read collection strongest result; dispatch to updated outer ids."
  [collection-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [cv (net/network-cell-strongest network collection-id)
          strongest-result (when (strongest/compound-subnet-continuation? cv) cv)
          sub (when strongest-result (strongest/continuation-subnet strongest-result))
          updated* (when strongest-result (strongest/continuation-updated* strongest-result))]
       (if (or (value/unusable? cv) (nil? updated*))
         []
         (reduce (fn [msgs outer-id]
                   (if-let [m (avatar-strongest-message sub network outer-id)]
                     (conj msgs m)
                     msgs))
                 []
                 @updated*))))
   [collection-id]
   []))

(defn p:cons
  "Install `p:car`, `p:cdr`, and `c:linked-list` for one collection cell.
  Returns `[linked-list-prop-id network]`."
  [head-id tail-id collection-id]
  (fn [network]
    (let [n (-> network
              (net/install-net (p:car head-id collection-id))
              (net/install-net (p:cdr tail-id collection-id)))]
      ((c:linked-list collection-id) n))))
