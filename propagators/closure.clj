(ns propagators.closure
  (:require [propagators.boundary :as boundary]
            [propagators.cells.diff :refer [diff-internal-output-cells]]
            [propagators.cells.value :as value]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network :refer [network-cell-strongest
                                         inner-ids-in inner-ids-out]]
            [propagators.propagator :as prop]))

(defrecord Closure [f net boundary])

(def current-item-key :closure/current-item)

(defn closure?
  [x]
  (and (map? x)
       (contains? x :f)
       (contains? x :net)))

(defn closure [f n]
  (->Closure f n nil))

(defn closure-f [c] (:f c))
(defn closure-net [c] (:net c))

(defn current-item
  "Activation-local item bound by `p:bind-network`."
  [closure-net]
  (net/network-dict-entry closure-net current-item-key))

(defn- with-current-item
  [closure-net item]
  (net/assoc-net-dict-entry closure-net current-item-key item))

(defn- strip-current-item
  [n]
  (if (net/network? n)
    (net/net-with-dict n (dissoc (net/net-dict-or-empty n) current-item-key))
    n))

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

(defn p:apply-network
  "Apply a closure-valued cell to a network-valued cell, emitting an expanded network value.

  This is declaration-only: it invokes the closure as a network transformer and
  does not run the expanded network or install it into the outer graph."
  [closure-id network-id out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [closure-cv (network-cell-strongest network closure-id)
           closure-value (value/value-payload closure-cv)
           network-value (network-cell-strongest network network-id)]
       (cond
         (or (value/unusable? closure-cv)
             (nil? closure-value)
             (value/unusable? network-value))
         []

         (not (net/network? network-value))
         [(message out-id value/contradiction)]

         :else
         (let [expanded (apply-network-closure closure-value network-value)]
           [(message out-id
                     (if (net/network? expanded)
                       expanded
                       value/contradiction))]))))
   [closure-id network-id]
   [out-id]))

(defn p:when-network
  "Conditionally apply a declaration closure to a network-valued cell.

  This is declaration-only. A true condition expands `acc-net-id` through the
  closure-valued `expander-id` and emits the expanded network to `out-net-id`.
  A false condition passes the accumulator network through unchanged. Nothing
  waits; contradiction emits contradiction.
  "
  [condition-id expander-id acc-net-id out-net-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [condition (network-cell-strongest network condition-id)
           acc-net (network-cell-strongest network acc-net-id)]
       (cond
         (value/nothing? condition)
         []

         (value/contradiction? condition)
         [(message out-net-id value/contradiction)]

         (not (net/network? acc-net))
         [(message out-net-id value/contradiction)]

         (= false condition)
         [(message out-net-id acc-net)]

         (= true condition)
         (let [expander-cv (network-cell-strongest network expander-id)
               expander-value (value/value-payload expander-cv)]
           (cond
             (or (value/nothing? expander-cv)
                 (nil? expander-value))
             []

             (value/contradiction? expander-cv)
             [(message out-net-id value/contradiction)]

             :else
             (let [expanded (apply-network-closure expander-value acc-net)]
               [(message out-net-id
                         (if (net/network? expanded)
                           expanded
                           value/contradiction))])))

         :else
         [(message out-net-id value/contradiction)])))
   [condition-id expander-id acc-net-id]
   [out-net-id]))

(defn p:when-apply-network
  "One-armed conditional network application.

  True applies a declaration closure to `acc-net-id` and emits to `out-net-id`.
  False or nothing emits no message. This is the network-valued counterpart to
  a one-armed `when`, unlike `p:when-network` whose false branch passes the
  accumulator through.
  "
  [condition-id expander-id acc-net-id out-net-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [condition (network-cell-strongest network condition-id)]
       (cond
         (value/nothing? condition)
         []

         (= false condition)
         []

         (value/contradiction? condition)
         [(message out-net-id value/contradiction)]

         (= true condition)
         (let [acc-net (network-cell-strongest network acc-net-id)
               expander-cv (network-cell-strongest network expander-id)
               expander-value (value/value-payload expander-cv)]
           (cond
             (not (net/network? acc-net))
             [(message out-net-id value/contradiction)]

             (or (value/nothing? expander-cv)
                 (nil? expander-value))
             []

             (value/contradiction? expander-cv)
             [(message out-net-id value/contradiction)]

             (not (closure? expander-value))
             [(message out-net-id value/contradiction)]

             :else
             (let [expanded (apply-network-closure expander-value acc-net)]
               [(message out-net-id
                         (if (net/network? expanded)
                           expanded
                           value/contradiction))])))

         :else
         [(message out-net-id value/contradiction)])))
   [condition-id expander-id acc-net-id]
   [out-net-id]))

(defn bind-network-closure
  [expander-value item]
  (closure
   (fn [_bound-net input-ids output-ids declaration-net]
     (-> ((closure-f expander-value)
          (with-current-item (closure-net expander-value) item)
          input-ids
          output-ids
          declaration-net)
         strip-current-item))
   (with-current-item net/empty-net item)))

(defn p:bind-network
  "Bind the current item into a network expander closure.

  The item is carried in the closure net while the bound closure runs and is
  stripped from the emitted declaration network.
  "
  [expander-id item-id bound-expander-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [expander-cv (network-cell-strongest network expander-id)
           expander-value (value/value-payload expander-cv)
           item (network-cell-strongest network item-id)]
       (cond
         (or (value/nothing? expander-cv)
             (nil? expander-value)
             (value/nothing? item))
         []

         (or (value/contradiction? expander-cv)
             (value/contradiction? item))
         [(message bound-expander-id value/contradiction)]

         (not (closure? expander-value))
         [(message bound-expander-id value/contradiction)]

         :else
         [(message bound-expander-id
                   (bind-network-closure expander-value item))])))
   [expander-id item-id]
   [bound-expander-id]))

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
