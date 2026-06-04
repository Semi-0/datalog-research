(ns propagators.closure
  (:require [propagators.boundary :as boundary]
            [propagators.cells.diff :refer [diff-internal-output-cells]]
            [propagators.cells.value :as value]
            [propagators.network :as net]
            [propagators.network :refer [network-cell-strongest
                                         inner-ids-in inner-ids-out]]
            [propagators.propagator :as prop]))

(defrecord Closure [f net boundary])

(defn closure?
  [x]
  (and (map? x)
       (contains? x :f)
       (contains? x :net)))

(defn closure [f n]
  (->Closure f n nil))

(defn closure-f [c] (:f c))
(defn closure-net [c] (:net c))

(defn closure-boundary
  "Optional avatar map on a closure value."
  [c]
  (:boundary c))

(defn apply-network-closure [closure-value external-network]
  ((closure-f closure-value)
   (closure-net closure-value)
   (vec (inner-ids-in external-network))
   (vec (inner-ids-out external-network))
   external-network))

(defn- boundary-nodes [closure-cell-id nodes]
  (vec (remove #(= closure-cell-id %) nodes)))

(def create-boundary-outputs boundary/create-boundary-outputs)
(def create-boundary-inputs boundary/create-boundary-inputs)

(defn compound-activate
  "Compound propagator body.

  `closure-in-id` strongest holds a `Closure` record (`:f`, `:net`); boundary
  cells are the other ports."
  [closure-in-id]
  (fn [input-ids output-ids network]
    (let [closure-cv (network-cell-strongest network closure-in-id)
          closure-payload (value/value-payload closure-cv)
          ins (boundary-nodes closure-in-id input-ids)
          outs (vec output-ids)
          in-vals (mapv #(network-cell-strongest network %) ins)]
      (if (or (value/unusable? closure-cv)
              (value/any-unusable-values? in-vals)
              (nil? closure-payload))
        []
        (-> network
            (create-boundary-outputs outs)
            (create-boundary-inputs ins)
            (#(apply-network-closure closure-payload %))
            (#(boundary/run-internal-network ins %))
            (diff-internal-output-cells network outs))))))

(defn p:apply-closure
  "Apply a closure-valued cell to input cells and one output cell."
  [closure-id & node-ids]
  (let [nodes (vec node-ids)]
    (prop/compound-propagator closure-id (vec (butlast nodes)) [(last nodes)])))

(defn primitive-closure
  "Build a closure that installs one primitive propagator from all inputs to one output."
  [f]
  (closure
   (fn [_closure-net input-ids output-ids network]
     (let [[out-id] output-ids
           [_ n'] ((apply (prop/primitive-propagator f)
                          (conj (vec input-ids) out-id))
                   network)]
       n'))
   net/empty-net))

(defn guarded-primitive-closure
  "Build a primitive closure that emits nothing while any input is unusable."
  [f]
  (primitive-closure
   (fn [& args]
     (if (apply value/any-unusable-values? args)
       value/nothing
       (apply f args)))))
