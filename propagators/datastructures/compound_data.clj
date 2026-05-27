(ns propagators.datastructures.compound_data
  "Linked-list propagators over compound subnet cells."
  (:require [propagators.cells.compound-merge :as cm]
            [propagators.cells.value :as value]
            [propagators.datastructures.compound_subnet :as subnet]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

;; Re-export subnet API for callers that only require this namespace.
(def compound-data? subnet/compound-data?)
(def compound-subnet-state? subnet/compound-subnet-state?)
(def compound-strongest-result? subnet/compound-strongest-result?)
(def empty-compound-subnet subnet/empty-compound-subnet)
(def merge-compound-data subnet/merge-compound-data)
(def avatar-strongest subnet/avatar-strongest)
(def compound-subnet-strongest cm/compound-subnet-strongest)
(def compound-update subnet/compound-update)
(def state-subnet subnet/state-subnet)
(def state-out-ids subnet/state-out-ids)
(def strongest-subnet subnet/strongest-subnet)
(def strongest-updated* subnet/strongest-updated*)

(defn- avatar-strongest-message [subnet network outer-id]
  (when (and (subnet/dispatch-target? network outer-id)
             (get (net/net-dict-or-empty subnet) outer-id))
    (let [avatar-id (get (net/net-dict-or-empty subnet) outer-id)]
      (message outer-id (net/network-cell-strongest subnet avatar-id)))))

(defn p:car
  "Write `{:head elem-id}` compound-data update to collection."
  [elem-id collection-id]
  (prop/construct-propagator
   (fn [_inputs _outputs _network]
     [(message collection-id (subnet/compound-update {:head elem-id}))])
   [elem-id]
   [collection-id]))

(defn p:cdr
  "Write `{:tail elem-id}` compound-data update to collection."
  [elem-id collection-id]
  (prop/construct-propagator
   (fn [_inputs _outputs _network]
     [(message collection-id (subnet/compound-update {:tail elem-id}))])
   [elem-id]
   [collection-id]))

(defn c:linked-list
  "Constraint: read collection strongest result; dispatch to updated outer ids."
  [collection-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [cv (net/network-cell-strongest network collection-id)
           strongest (when (subnet/compound-strongest-result? cv) cv)
           sub (when strongest (subnet/strongest-subnet strongest))
           updated* (when strongest (subnet/strongest-updated* strongest))]
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
