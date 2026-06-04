(ns propagators.recursive
  "Recursive compound propagation over activation-local network values."
  (:require [propagators.boundary :as boundary]
            [propagators.cells.diff :as diff]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def self-key :recursive/self)
(def depth-key :recursive/depth)

(defn- write-outputs
  [network output-ids v]
  (reduce #(nb/seed-cell %1 %2 v) network output-ids))

(defn recursive-closure
  "Build a closure whose body may install recursive calls to `:recursive/self`.

  `step-f` receives `{:closure-net :self-id :input-ids :output-ids :network
  :depth}` and returns an updated activation-local network value."
  ([step-f]
   (recursive-closure step-f {}))
  ([step-f {:keys [max-depth]
            :or {max-depth 1024}}]
   (closure/closure
    (fn [closure-net input-ids output-ids network]
      (let [depth (or (net/network-dict-entry closure-net depth-key) 0)
            self-id (net/network-dict-entry closure-net self-key)]
        (if (>= depth max-depth)
          (write-outputs network output-ids value/contradiction)
          (step-f {:closure-net closure-net
                   :self-id self-id
                   :input-ids (vec input-ids)
                   :output-ids (vec output-ids)
                   :network network
                   :depth depth}))))
    net/empty-net)))

(defn- closure-net-with-frame
  [closure-net self-id depth]
  (-> closure-net
      (net/assoc-net-dict-entry self-key self-id)
      (net/assoc-net-dict-entry depth-key depth)))

(defn- with-closure-net
  [closure-value closure-net]
  (assoc closure-value :net closure-net))

(defn- prepare-recursive-frame
  [network closure-value]
  (let [current-depth (or (net/network-dict-entry
                           (closure/closure-net closure-value)
                           depth-key)
                          0)
        self-id (ids/new-node-id)
        current-closure-net (closure-net-with-frame
                             (closure/closure-net closure-value)
                             self-id
                             current-depth)
        child-closure-net (closure-net-with-frame
                           (closure/closure-net closure-value)
                           self-id
                           (inc current-depth))
        child-closure (with-closure-net closure-value child-closure-net)
        network' (-> network
                     (nb/install-cell self-id child-closure child-closure)
                     (net/assoc-net-dict-entry self-key self-id)
                     (net/assoc-net-dict-entry depth-key current-depth))]
    {:network network'
     :closure-net current-closure-net}))

(defn- recursive-activate
  [closure-id arg-ids out-id]
  (fn [_inputs _outputs outer-network]
    (let [closure-cv (net/network-cell-strongest outer-network closure-id)
          closure-value (value/value-payload closure-cv)
          arg-values (mapv #(net/network-cell-strongest outer-network %) arg-ids)]
      (if (or (value/unusable? closure-cv)
              (apply value/any-unusable-values? arg-values)
              (nil? closure-value))
        []
        (let [with-boundary (-> outer-network
                                (boundary/create-boundary-outputs [out-id])
                                (boundary/create-boundary-inputs arg-ids))
              input-avatars (mapv #(net/lookup-inner-in with-boundary %) arg-ids)
              output-avatars [(net/lookup-inner-out with-boundary out-id)]
              {:keys [network closure-net]} (prepare-recursive-frame
                                             with-boundary
                                             closure-value)
              installed ((closure/closure-f closure-value)
                         closure-net
                         input-avatars
                         output-avatars
                         network)
              after (boundary/run-internal-network input-avatars installed)]
          (diff/diff-internal-output-cells after outer-network [out-id]))))))

(defn- normalize-arg-ids
  [arg-ids]
  (if (ids/node-id? arg-ids)
    [arg-ids]
    (vec arg-ids)))

(defn p:recursive-compound
  "Apply a recursive closure-valued cell to argument cells and one output cell."
  [closure-id arg-ids out-id]
  (let [args (normalize-arg-ids arg-ids)]
    (prop/construct-propagator
     (recursive-activate closure-id args out-id)
     (into [closure-id] args)
     [out-id])))
